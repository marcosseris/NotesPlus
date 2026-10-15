package com.notesplus.app.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import com.notesplus.app.ink.InkDocument
import com.notesplus.app.ink.InkGeometry
import com.notesplus.app.ink.InkMerge
import com.notesplus.app.ink.ShapeBuilder
import com.notesplus.app.ink.ShapeKind
import com.notesplus.app.ink.ShapeRecognizer
import com.notesplus.app.ink.Stroke
import com.notesplus.app.ink.ToolType
import androidx.input.motionprediction.MotionEventPredictor
import java.util.UUID
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The note canvas: shows the pages of a PDF (or blank notebook) in a vertically
 * scrolling, zoomable strip and lets a stylus draw on them.
 *
 * Input policy (what makes it feel right with a pen):
 *  - Stylus contact always draws (or erases). Finger touches that arrive while a stylus is
 *    down or has very recently been near the screen are treated as a resting palm and ignored.
 *  - Fingers scroll and pinch-zoom; optionally they may draw too ("finger drawing").
 *  - The stylus side button, or an eraser-tip, switches to the eraser for that stroke.
 *  - Stylus events are requested unbuffered and historical samples are consumed, so strokes
 *    track the pen tip closely.
 */
class DocumentView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    enum class Tool { PEN, HIGHLIGHTER, ERASER, SHAPE, PAN }

    interface Listener {
        fun onInkChanged(doc: InkDocument, canUndo: Boolean, canRedo: Boolean)
        fun onViewportChanged(currentPage: Int, pageCount: Int, zoom: Float)
        /** A keyboard shortcut asked for a tool (p/h/e/s/space) – the host updates its state. */
        fun onToolShortcut(tool: Tool) {}
        /** A hand-drawn stroke was snapped to a shape (for a small toast / haptic in the host). */
        fun onShapeSnapped() {}
    }

    var listener: Listener? = null

    // ----------------------------------------------------------------- settings
    var tool: Tool = Tool.PEN
        set(value) { field = value; if (value != Tool.ERASER) eraserCursor = null; invalidate() }
    var penColor: Int = 0xFF1C1C1E.toInt()
    var penWidth: Float = 2.2f
    var highlighterColor: Int = 0xFFFFE627.toInt()
    var highlighterWidth: Float = 14f
    /** Eraser radius in page points. */
    var eraserRadius: Float = 10f
    var fingerDraws: Boolean = false
    var stylusButtonErases: Boolean = true
    /** Shape drawn by the SHAPE tool. */
    var shapeKind: ShapeKind = ShapeKind.LINE
    /** Pause the pen at the end of a stroke to turn it into a clean line / shape. */
    var autoShape: Boolean = true
    /** How long the pen has to rest before a stroke is snapped. */
    var autoShapeHoldMs: Long = 550L
    /** Use the platform motion predictor to draw a short predicted tail (cuts perceived lag). */
    var motionPrediction: Boolean = true

    // ----------------------------------------------------------------- document
    private var source: PageSource? = null
    private var renderer: PageRenderer? = null
    var ink: InkDocument = InkDocument()
        private set
    private val undo = UndoManager()
    val canUndo get() = undo.canUndo
    val canRedo get() = undo.canRedo

    // ----------------------------------------------------------------- layout
    private val density = resources.displayMetrics.density
    private val marginPx = 12f * density
    private val gapPx = 14f * density
    private val pageRects = ArrayList<RectF>()   // document space (px at zoom 1)
    private var baseScale = 1f                    // px per point at zoom 1
    private var docWidth = 0f
    private var docHeight = 0f
    private var zoom = 1f
    /** Zoom at which the first page is fully visible (can be < 1 in landscape). */
    private var fitPageZoom = 1f
    private var minZoom = 1f
    private val maxZoom = 8f
    private var offX = 0f
    private var offY = 0f
    private var pendingScrollFraction = -1f

    // ----------------------------------------------------------------- paints
    private val bgColor = 0xFF8E9AAB.toInt()
    private val pagePaint = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val shadowPaint = Paint().apply { color = 0x33000000; style = Paint.Style.FILL }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val penPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val hlPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
    }
    private val eraserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * density; color = 0xAA333333.toInt() }
    private val eraserFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0x22000000 }
    private val pathCache = HashMap<String, Path>()

    // ----------------------------------------------------------------- input state
    private var drawingPointerId = -1
    private var drawingIsStylus = false
    private var drawingPage = -1
    private var drawingMode = ToolType.PEN
    private var erasing = false
    private val curPts = FloatList()
    private var curColor = 0
    private var curWidth = 0f
    private var lastEraseX = 0f
    private var lastEraseY = 0f
    private val erasedThisGesture = ArrayList<Stroke>()
    private var lastStylusTime = 0L
    private var eraserCursor: FloatArray? = null  // screen x, y, radius px
    private var penCursor: FloatArray? = null     // screen x, y (stylus hovering)

    // Shape tool drag and hold-to-snap.
    private var shapeStart: FloatArray? = null    // page coords
    private var shapeEnd: FloatArray? = null
    private var shapeConstrain = false
    private var strokeConsumed = false            // stroke already committed by a snap; ignore until up
    private var lastMoveTime = 0L
    private val holdRunnable = Runnable { trySnapCurrentStroke() }
    private var predictor: MotionEventPredictor? = null
    private var predictedPts = FloatArray(0)

    private var gestureActive = false
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var velocity: VelocityTracker? = null
    private val scroller = OverScroller(context)
    private var scaling = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private val maxFling = ViewConfiguration.get(context).scaledMaximumFlingVelocity

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean { scaling = true; return true }
        override fun onScale(d: ScaleGestureDetector): Boolean { zoomBy(d.scaleFactor, d.focusX, d.focusY); return true }
        override fun onScaleEnd(d: ScaleGestureDetector) { scaling = false; schedulePatches() }
    }).apply { isQuickScaleEnabled = false }

    private val tapDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            val target = if (zoom > minZoom * 1.05f) minZoom else max(1f, minZoom) * 2.2f
            animateZoomTo(target, e.x, e.y)
            return true
        }
    })

    private val visTmp = RectF()
    private val srTmp = RectF()
    private val patchRunnable = Runnable { requestPatchesNow() }
    private val viewportRunnable = Runnable { notifyViewport(force = false) }
    private var lastNotifiedPage = -1
    private var lastNotifiedZoom = -1f

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    // ================================================================== public API

    fun setDocument(source: PageSource, ink: InkDocument, initialPage: Int = 0) {
        this.renderer?.close()
        this.source?.let { if (it !== source) runCatching { it.close() } }
        this.source = source
        this.ink = ink
        undo.clear()
        pathCache.clear()
        renderer = PageRenderer(source) { postInvalidateOnAnimation() }
        zoom = 1f; offX = 0f; offY = 0f
        relayout()
        zoom = minZoom
        clampOffsets()
        if (initialPage > 0) scrollToPage(initialPage)
        lastNotifiedPage = -1
        notifyViewport(force = true)
        invalidate()
    }

    /** Replaces the ink (e.g. after a sync merge), keeping any stroke being drawn. */
    fun setInk(doc: InkDocument) {
        val ids = HashSet<String>()
        doc.pages.forEach { p -> p.strokes.forEach { ids += it.id } }
        pathCache.keys.retainAll(ids)
        ink = doc
        (source as? BlankPageSource)?.let { b ->
            if (doc.pageCount > b.pageCount) { b.pageCount = doc.pageCount; relayout() }
            b.paper = doc.paper
        }
        invalidate()
    }

    /** Merges a document written by the sync engine into what is on screen. */
    fun mergeExternal(fileDoc: InkDocument) = setInk(InkMerge.merge(ink, fileDoc))

    fun undo() { if (!undo.canUndo) return; finishStrokeIfAny(); ink = undo.undo(ink); emitInkChanged(); invalidate() }
    fun redo() { if (!undo.canRedo) return; finishStrokeIfAny(); ink = undo.redo(ink); emitInkChanged(); invalidate() }

    fun addPage(): Int {
        val b = source as? BlankPageSource ?: return -1
        b.pageCount += 1
        ink = ink.withPageCount(b.pageCount)
        relayout()
        emitInkChanged()
        scrollToPage(b.pageCount - 1)
        return b.pageCount - 1
    }

    val pageCount: Int get() = source?.pageCount ?: 0

    fun currentPage(): Int {
        if (pageRects.isEmpty()) return 0
        val cy = (height / 2f + offY) / zoom
        var best = 0
        for (i in pageRects.indices) if (pageRects[i].top <= cy) best = i
        return best
    }

    fun scrollToPage(index: Int) {
        val r = pageRects.getOrNull(index) ?: return
        scroller.forceFinished(true)
        offY = (r.top - marginPx) * zoom
        clampOffsets()
        invalidate()
        schedulePatches()
        notifyViewport(force = true)
    }

    fun zoomLevel() = zoom

    /** Fit the whole page on screen. */
    fun fitPage() { animateZoomTo(minZoom, width / 2f, height / 2f) }

    /** Fit the page width to the screen. */
    fun fitWidth() { animateZoomTo(1f, width / 2f, height / 2f) }

    fun resetZoom() = fitPage()

    fun release() {
        renderer?.close(); renderer = null
        source = null
        removeCallbacks(patchRunnable); removeCallbacks(viewportRunnable); removeCallbacks(holdRunnable)
    }

    // ================================================================== layout

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (oldh > 0 && docHeight > 0) pendingScrollFraction = offY / (docHeight * zoom)
        relayout()
        renderer?.let { it.previewWidthPx = min(1200, max(500, (w * 0.9f).toInt())); it.invalidateAll() }
        schedulePatches()
    }

    private fun relayout() {
        val src = source ?: return
        if (width == 0) return
        pageRects.clear()
        var maxW = 1f
        for (i in 0 until src.pageCount) maxW = max(maxW, src.pageSize(i).w)
        baseScale = (width - 2 * marginPx) / maxW
        var y = marginPx
        for (i in 0 until src.pageCount) {
            val s = src.pageSize(i)
            val w = s.w * baseScale; val h = s.h * baseScale
            val left = (width - w) / 2f
            pageRects.add(RectF(left, y, left + w, y + h))
            y += h + gapPx
        }
        docWidth = width.toFloat()
        docHeight = y - gapPx + marginPx
        // Zoom that shows the tallest-of-the-first-pages completely (matters in landscape on a tablet).
        val firstH = pageRects.first().height() + 2 * marginPx
        fitPageZoom = if (height > 0) min(1f, height / firstH) else 1f
        minZoom = min(1f, fitPageZoom)
        if (zoom < minZoom) zoom = minZoom
        if (pendingScrollFraction >= 0f) { offY = pendingScrollFraction * docHeight * zoom; pendingScrollFraction = -1f }
        clampOffsets()
    }

    private fun clampOffsets() {
        val maxX = max(0f, docWidth * zoom - width)
        val maxY = max(0f, docHeight * zoom - height)
        offX = offX.coerceIn(0f, maxX)
        offY = offY.coerceIn(0f, maxY)
    }

    /** Horizontal offset including centring when the content is narrower than the view. */
    private fun effOffX(): Float {
        val contentW = docWidth * zoom
        return if (contentW < width) -(width - contentW) / 2f else offX
    }

    private fun pageScreenRect(i: Int, out: RectF = RectF()): RectF {
        val r = pageRects[i]
        val ox = effOffX()
        out.set(r.left * zoom - ox, r.top * zoom - offY, r.right * zoom - ox, r.bottom * zoom - offY)
        return out
    }

    private fun pageAtScreen(sx: Float, sy: Float): Int {
        val dx = (sx + effOffX()) / zoom; val dy = (sy + offY) / zoom
        for (i in pageRects.indices) if (pageRects[i].contains(dx, dy)) return i
        return -1
    }

    /** Converts a screen point to page-point coordinates of page [i], clamped to the page. */
    private fun screenToPage(i: Int, sx: Float, sy: Float, out: FloatArray) {
        val r = pageRects[i]
        val s = source!!.pageSize(i)
        val px = ((sx + effOffX()) / zoom - r.left) / baseScale
        val py = ((sy + offY) / zoom - r.top) / baseScale
        out[0] = px.coerceIn(0f, s.w); out[1] = py.coerceIn(0f, s.h)
    }

    // ================================================================== zoom / scroll

    private fun zoomBy(factor: Float, fx: Float, fy: Float) {
        val newZoom = (zoom * factor).coerceIn(minZoom, maxZoom)
        if (newZoom == zoom) return
        // Keep the document point under the focus fixed.
        val docX = (fx + effOffX()) / zoom; val docY = (fy + offY) / zoom
        zoom = newZoom
        offX = docX * zoom - fx; offY = docY * zoom - fy
        clampOffsets()
        invalidate()
        postViewportNotify()
    }

    private fun animateZoomTo(target: Float, fx: Float, fy: Float) {
        val start = zoom
        val t0 = SystemClock.uptimeMillis()
        val dur = 220L
        val r = object : Runnable {
            override fun run() {
                val t = ((SystemClock.uptimeMillis() - t0).toFloat() / dur).coerceIn(0f, 1f)
                val e = 1f - (1f - t) * (1f - t)
                val z = start + (target - start) * e
                zoomBy(z / zoom, fx, fy)
                if (t < 1f) postOnAnimation(this) else schedulePatches()
            }
        }
        postOnAnimation(r)
    }

    private fun scrollBy(dx: Float, dy: Float) {
        offX += dx; offY += dy
        clampOffsets()
        invalidate()
        postViewportNotify()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            offX = scroller.currX.toFloat(); offY = scroller.currY.toFloat()
            clampOffsets()
            postInvalidateOnAnimation()
            postViewportNotify()
            if (scroller.isFinished) schedulePatches()
        }
    }

    private fun schedulePatches() {
        removeCallbacks(patchRunnable)
        postDelayed(patchRunnable, 90)
    }

    private fun requestPatchesNow() {
        val src = source ?: return
        val rnd = renderer ?: return
        if (!src.isPdf || scaling || gestureActive || !scroller.isFinished) return
        val visible = LinkedHashMap<Int, RectF>()
        val tmp = RectF()
        for (i in pageRects.indices) {
            pageScreenRect(i, tmp)
            if (tmp.bottom < 0 || tmp.top > height) continue
            val s = src.pageSize(i)
            val scale = baseScale * zoom
            val vis = RectF(
                ((max(0f, tmp.left) - tmp.left) / scale).coerceIn(0f, s.w),
                ((max(0f, tmp.top) - tmp.top) / scale).coerceIn(0f, s.h),
                ((min(width.toFloat(), tmp.right) - tmp.left) / scale).coerceIn(0f, s.w),
                ((min(height.toFloat(), tmp.bottom) - tmp.top) / scale).coerceIn(0f, s.h)
            )
            if (vis.width() > 0 && vis.height() > 0) visible[i] = vis
        }
        rnd.requestPatches(baseScale * zoom, visible)
    }

    private fun postViewportNotify() {
        removeCallbacks(viewportRunnable)
        postDelayed(viewportRunnable, 60)
    }

    private fun notifyViewport(force: Boolean) {
        val p = currentPage()
        if (force || p != lastNotifiedPage || abs(zoom - lastNotifiedZoom) > 0.01f) {
            lastNotifiedPage = p; lastNotifiedZoom = zoom
            listener?.onViewportChanged(p, pageCount, zoom)
        }
    }

    // ================================================================== drawing

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(bgColor)
        val src = source ?: return
        val rnd = renderer
        val sr = srTmp
        val scale = baseScale * zoom
        var anyPatchMissing = false
        for (i in pageRects.indices) {
            pageScreenRect(i, sr)
            if (sr.bottom < -gapPx || sr.top > height + gapPx) continue
            // Page body with a soft shadow.
            canvas.drawRect(sr.left + 2 * density, sr.top + 3 * density, sr.right + 2 * density, sr.bottom + 3 * density, shadowPaint)
            canvas.drawRect(sr, pagePaint)

            if (src.isPdf && rnd != null) {
                val s = src.pageSize(i)
                val vis = visTmp
                vis.set(
                    ((max(0f, sr.left) - sr.left) / scale).coerceIn(0f, s.w),
                    ((max(0f, sr.top) - sr.top) / scale).coerceIn(0f, s.h),
                    ((min(width.toFloat(), sr.right) - sr.left) / scale).coerceIn(0f, s.w),
                    ((min(height.toFloat(), sr.bottom) - sr.top) / scale).coerceIn(0f, s.h)
                )
                val patch = rnd.patch(i, scale, vis)
                val preview = rnd.preview(i)
                if (patch != null) {
                    if (preview != null) canvas.drawBitmap(preview, null, sr, bitmapPaint)
                    canvas.drawBitmap(patch.bitmap, sr.left + patch.pageRect.left * scale, sr.top + patch.pageRect.top * scale, bitmapPaint)
                } else {
                    anyPatchMissing = true
                    if (preview != null) canvas.drawBitmap(preview, null, sr, bitmapPaint)
                }
            } else if (src is BlankPageSource) {
                val save = canvas.save()
                canvas.clipRect(sr)
                canvas.translate(sr.left, sr.top)
                canvas.scale(scale, scale)
                BlankPageSource.drawPaper(canvas, src.pageSize(i), src.paper)
                canvas.restoreToCount(save)
            }

            // Ink.
            val page = ink.page(i)
            val hasCurrent = drawingPointerId != -1 && drawingPage == i && !erasing && !strokeConsumed && (curPts.size >= 3 || shapeStart != null)
            if ((page != null && page.strokes.isNotEmpty()) || hasCurrent) {
                val save = canvas.save()
                canvas.clipRect(sr)
                canvas.translate(sr.left, sr.top)
                canvas.scale(scale, scale)
                page?.strokes?.forEach { drawStroke(canvas, it) }
                if (hasCurrent) drawCurrentStroke(canvas)
                canvas.restoreToCount(save)
            }
        }
        eraserCursor?.let { c -> canvas.drawCircle(c[0], c[1], c[2], eraserFill); canvas.drawCircle(c[0], c[1], c[2], eraserPaint) }
        penCursor?.let { c -> canvas.drawCircle(c[0], c[1], 2.5f * density, eraserPaint) }
        if (anyPatchMissing && src.isPdf && !scaling && !gestureActive && scroller.isFinished) schedulePatches()
    }

    private fun pathFor(s: Stroke): Path = pathCache.getOrPut(s.id) { buildPath(s.tool, s.pts, s.width) }

    private fun buildPath(tool: ToolType, pts: FloatArray, width: Float): Path {
        val path = Path()
        when (tool) {
            ToolType.PEN -> {
                val poly = InkGeometry.outline(InkGeometry.smooth(pts), width)
                if (poly.size >= 6) {
                    path.moveTo(poly[0], poly[1])
                    var i = 2
                    while (i < poly.size) { path.lineTo(poly[i], poly[i + 1]); i += 2 }
                    path.close()
                }
            }
            ToolType.HIGHLIGHTER -> {
                val n = pts.size / 3
                if (n == 0) return path
                path.moveTo(pts[0], pts[1])
                if (n == 1) path.lineTo(pts[0] + 0.1f, pts[1])
                else if (n == 2) path.lineTo(pts[3], pts[4])
                else {
                    for (i in 1 until n - 1) {
                        val x0 = pts[i * 3]; val y0 = pts[i * 3 + 1]
                        val x1 = pts[(i + 1) * 3]; val y1 = pts[(i + 1) * 3 + 1]
                        path.quadTo(x0, y0, (x0 + x1) / 2f, (y0 + y1) / 2f)
                    }
                    path.lineTo(pts[(n - 1) * 3], pts[(n - 1) * 3 + 1])
                }
            }
        }
        return path
    }

    private fun drawStroke(c: Canvas, s: Stroke) {
        when (s.tool) {
            ToolType.PEN -> { penPaint.color = s.color; c.drawPath(pathFor(s), penPaint) }
            ToolType.HIGHLIGHTER -> {
                hlPaint.color = s.color; hlPaint.alpha = HL_ALPHA; hlPaint.strokeWidth = s.width
                c.drawPath(pathFor(s), hlPaint)
            }
        }
    }

    private fun drawCurrentStroke(c: Canvas) {
        val start = shapeStart
        if (start != null) {
            val end = shapeEnd ?: start
            penPaint.color = curColor
            for (line in ShapeBuilder.build(shapeKind, start[0], start[1], end[0], end[1], shapeConstrain)) {
                c.drawPath(buildPath(ToolType.PEN, ShapeBuilder.toStrokePoints(line), curWidth), penPaint)
            }
            return
        }
        val pts = if (predictedPts.isEmpty()) curPts.toArray() else curPts.toArray() + predictedPts
        when (drawingMode) {
            ToolType.PEN -> { penPaint.color = curColor; c.drawPath(buildPath(ToolType.PEN, pts, curWidth), penPaint) }
            ToolType.HIGHLIGHTER -> {
                hlPaint.color = curColor; hlPaint.alpha = HL_ALPHA; hlPaint.strokeWidth = curWidth
                c.drawPath(buildPath(ToolType.HIGHLIGHTER, pts, curWidth), hlPaint)
            }
        }
    }

    // ================================================================== input

    // Taps on the canvas are drawing input, not clicks; accessibility actions live in the toolbar.
    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val action = ev.actionMasked
        val idx = ev.actionIndex
        val toolType = ev.getToolType(idx)
        val stylus = toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER
        val now = SystemClock.uptimeMillis()
        if (source == null) return false

        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (stylus) {
                    lastStylusTime = now
                    if (drawingPointerId != -1) return true
                    cancelGesture()
                    if (tool == Tool.PAN) { startGesture(ev); return true }
                    val erase = toolType == MotionEvent.TOOL_TYPE_ERASER ||
                        tool == Tool.ERASER ||
                        (stylusButtonErases && (ev.buttonState and (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_SECONDARY)) != 0)
                    if (beginStroke(ev, idx, erase, isStylus = true)) requestUnbufferedDispatch(ev)
                    return true
                }
                // Finger.
                if (drawingPointerId != -1 && drawingIsStylus) return true          // palm while writing
                if (now - lastStylusTime < PALM_WINDOW_MS && !fingerDraws) return true // palm just after writing
                if (fingerDraws && tool != Tool.PAN && ev.pointerCount == 1 && drawingPointerId == -1) {
                    beginStroke(ev, idx, tool == Tool.ERASER, isStylus = false)
                    return true
                }
                if (drawingPointerId != -1 && !drawingIsStylus) cancelStroke()      // second finger: it's a gesture
                startGesture(ev)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (drawingPointerId != -1) {
                    val pi = ev.findPointerIndex(drawingPointerId)
                    if (pi >= 0 && !strokeConsumed) {
                        if (shapeStart != null) {
                            shapeConstrain = (ev.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0 || (ev.metaState and KeyEvent.META_SHIFT_ON) != 0
                            updateShapeEnd(ev.getX(pi), ev.getY(pi))
                        } else {
                            for (h in 0 until ev.historySize) addPoint(ev.getHistoricalX(pi, h), ev.getHistoricalY(pi, h), ev.getHistoricalPressure(pi, h))
                            addPoint(ev.getX(pi), ev.getY(pi), ev.getPressure(pi))
                            updatePrediction(ev)
                        }
                        if (drawingIsStylus) lastStylusTime = now
                        invalidate()
                    }
                    return true
                }
                if (gestureActive) {
                    scaleDetector.onTouchEvent(ev)
                    tapDetector.onTouchEvent(ev)
                    velocity?.addMovement(ev)
                    val (fx, fy) = fingerFocus(ev, excludeIndex = -1)
                    scrollBy(lastFocusX - fx, lastFocusY - fy)
                    lastFocusX = fx; lastFocusY = fy
                    return true
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val pid = ev.getPointerId(idx)
                if (pid == drawingPointerId) {
                    val pi = idx
                    if (!strokeConsumed) {
                        if (shapeStart != null) updateShapeEnd(ev.getX(pi), ev.getY(pi))
                        else {
                            for (h in 0 until ev.historySize) addPoint(ev.getHistoricalX(pi, h), ev.getHistoricalY(pi, h), ev.getHistoricalPressure(pi, h))
                            addPoint(ev.getX(pi), ev.getY(pi), ev.getPressure(pi))
                        }
                    }
                    if (drawingIsStylus) lastStylusTime = now
                    finishStroke()
                    return true
                }
                if (gestureActive) {
                    scaleDetector.onTouchEvent(ev)
                    tapDetector.onTouchEvent(ev)
                    velocity?.addMovement(ev)
                    if (action == MotionEvent.ACTION_UP || ev.pointerCount - 1 == 0) {
                        endGestureWithFling()
                    } else {
                        val (fx, fy) = fingerFocus(ev, excludeIndex = idx)
                        lastFocusX = fx; lastFocusY = fy
                    }
                    return true
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (drawingPointerId != -1) finishStroke()
                cancelGesture()
                return true
            }
        }
        return super.onTouchEvent(ev)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onHoverEvent(ev: MotionEvent): Boolean {
        val tt = ev.getToolType(0)
        if (tt == MotionEvent.TOOL_TYPE_STYLUS || tt == MotionEvent.TOOL_TYPE_ERASER) {
            lastStylusTime = SystemClock.uptimeMillis()
            val erase = tool == Tool.ERASER || tt == MotionEvent.TOOL_TYPE_ERASER ||
                (stylusButtonErases && (ev.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0)
            if (ev.actionMasked == MotionEvent.ACTION_HOVER_EXIT) {
                eraserCursor = null; penCursor = null; invalidate()
            } else if (erase) {
                penCursor = null
                eraserCursor = floatArrayOf(ev.x, ev.y, eraserRadius * baseScale * zoom)
                invalidate()
            } else {
                eraserCursor = null
                penCursor = floatArrayOf(ev.x, ev.y)
                invalidate()
            }
            return true
        }
        return super.onHoverEvent(ev)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val ctrl = event.isCtrlPressed
        when {
            ctrl && keyCode == KeyEvent.KEYCODE_Z && event.isShiftPressed -> { redo(); return true }
            ctrl && keyCode == KeyEvent.KEYCODE_Z -> { undo(); return true }
            ctrl && keyCode == KeyEvent.KEYCODE_Y -> { redo(); return true }
            ctrl && (keyCode == KeyEvent.KEYCODE_PLUS || keyCode == KeyEvent.KEYCODE_EQUALS) -> { zoomBy(1.2f, width / 2f, height / 2f); schedulePatches(); return true }
            ctrl && keyCode == KeyEvent.KEYCODE_MINUS -> { zoomBy(1 / 1.2f, width / 2f, height / 2f); schedulePatches(); return true }
            ctrl && keyCode == KeyEvent.KEYCODE_0 -> { fitPage(); return true }
            !ctrl && keyCode == KeyEvent.KEYCODE_P -> { listener?.onToolShortcut(Tool.PEN); return true }
            !ctrl && keyCode == KeyEvent.KEYCODE_H -> { listener?.onToolShortcut(Tool.HIGHLIGHTER); return true }
            !ctrl && keyCode == KeyEvent.KEYCODE_E -> { listener?.onToolShortcut(Tool.ERASER); return true }
            !ctrl && keyCode == KeyEvent.KEYCODE_S -> { listener?.onToolShortcut(Tool.SHAPE); return true }
            !ctrl && keyCode == KeyEvent.KEYCODE_SPACE -> { listener?.onToolShortcut(Tool.PAN); return true }
            keyCode == KeyEvent.KEYCODE_PAGE_DOWN -> { scrollToPage(currentPage() + 1); return true }
            keyCode == KeyEvent.KEYCODE_PAGE_UP -> { scrollToPage(currentPage() - 1); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ev.source and InputDevice.SOURCE_CLASS_POINTER != 0 && ev.actionMasked == MotionEvent.ACTION_SCROLL) {
            val v = ev.getAxisValue(MotionEvent.AXIS_VSCROLL)
            val h = ev.getAxisValue(MotionEvent.AXIS_HSCROLL)
            if (ev.metaState and KeyEvent.META_CTRL_ON != 0) {
                zoomBy(if (v > 0) 1.1f else 1f / 1.1f, ev.x, ev.y); schedulePatches()
            } else {
                scrollBy(-h * 64 * density, -v * 64 * density); schedulePatches()
            }
            return true
        }
        return super.onGenericMotionEvent(ev)
    }

    private fun fingerFocus(ev: MotionEvent, excludeIndex: Int): Pair<Float, Float> {
        var sx = 0f; var sy = 0f; var n = 0
        for (i in 0 until ev.pointerCount) {
            if (i == excludeIndex) continue
            val tt = ev.getToolType(i)
            if (tt == MotionEvent.TOOL_TYPE_STYLUS || tt == MotionEvent.TOOL_TYPE_ERASER) {
                if (tool != Tool.PAN) continue
            }
            sx += ev.getX(i); sy += ev.getY(i); n++
        }
        return if (n == 0) lastFocusX to lastFocusY else (sx / n) to (sy / n)
    }

    private fun startGesture(ev: MotionEvent) {
        scroller.forceFinished(true)
        gestureActive = true
        velocity?.recycle()
        velocity = VelocityTracker.obtain().also { it.addMovement(ev) }
        scaleDetector.onTouchEvent(ev)
        tapDetector.onTouchEvent(ev)
        val (fx, fy) = fingerFocus(ev, excludeIndex = -1)
        lastFocusX = fx; lastFocusY = fy
    }

    private fun endGestureWithFling() {
        val vt = velocity
        if (vt != null) {
            vt.computeCurrentVelocity(1000, maxFling.toFloat())
            val vx = vt.xVelocity; val vy = vt.yVelocity
            if (hypot(vx, vy) > minFling && !scaling) {
                scroller.fling(
                    offX.toInt(), offY.toInt(), -vx.toInt(), -vy.toInt(),
                    0, max(0, (docWidth * zoom - width).toInt()), 0, max(0, (docHeight * zoom - height).toInt())
                )
                postInvalidateOnAnimation()
            }
            vt.recycle()
        }
        velocity = null
        gestureActive = false
        schedulePatches()
    }

    private fun cancelGesture() {
        velocity?.recycle(); velocity = null
        gestureActive = false
        scaling = false
    }

    // ---------------------------------------------------------------- strokes

    private fun beginStroke(ev: MotionEvent, idx: Int, erase: Boolean, isStylus: Boolean): Boolean {
        val page = pageAtScreen(ev.getX(idx), ev.getY(idx))
        if (page < 0) return false
        drawingPointerId = ev.getPointerId(idx)
        drawingIsStylus = isStylus
        drawingPage = page
        erasing = erase
        strokeConsumed = false
        erasedThisGesture.clear()
        curPts.clear()
        predictedPts = FloatArray(0)
        shapeStart = null; shapeEnd = null
        penCursor = null
        if (!erase) {
            drawingMode = if (tool == Tool.HIGHLIGHTER) ToolType.HIGHLIGHTER else ToolType.PEN
            curColor = if (drawingMode == ToolType.HIGHLIGHTER) highlighterColor else penColor
            curWidth = if (drawingMode == ToolType.HIGHLIGHTER) highlighterWidth else penWidth
        }
        val p = FloatArray(2)
        screenToPage(page, ev.getX(idx), ev.getY(idx), p)
        when {
            erase -> { lastEraseX = p[0]; lastEraseY = p[1]; eraseAt(p[0], p[1], p[0], p[1]); eraserCursor = floatArrayOf(ev.getX(idx), ev.getY(idx), eraserRadius * baseScale * zoom) }
            tool == Tool.SHAPE -> { shapeStart = p.copyOf(); shapeEnd = p.copyOf(); shapeConstrain = false }
            else -> {
                curPts.add(p[0], p[1], normalizePressure(ev.getPressure(idx), isStylus))
                lastMoveTime = SystemClock.uptimeMillis()
                if (isStylus && motionPrediction) {
                    if (predictor == null) predictor = runCatching { MotionEventPredictor.newInstance(this) }.getOrNull()
                    predictor?.record(ev)
                }
            }
        }
        parent?.requestDisallowInterceptTouchEvent(true)
        return true
    }

    private fun updateShapeEnd(sx: Float, sy: Float) {
        val p = FloatArray(2)
        screenToPage(drawingPage, sx, sy, p)
        shapeEnd = p
    }

    private fun updatePrediction(ev: MotionEvent) {
        val pr = predictor ?: return
        if (!drawingIsStylus || !motionPrediction) return
        pr.record(ev)
        val predicted = pr.predict()
        if (predicted == null) { predictedPts = FloatArray(0); return }
        val out = FloatArray((predicted.historySize + 1) * 3)
        var k = 0
        val tmp = FloatArray(2)
        for (h in 0 until predicted.historySize) {
            screenToPage(drawingPage, predicted.getHistoricalX(h), predicted.getHistoricalY(h), tmp)
            out[k++] = tmp[0]; out[k++] = tmp[1]; out[k++] = normalizePressure(predicted.getHistoricalPressure(h), true)
        }
        screenToPage(drawingPage, predicted.x, predicted.y, tmp)
        out[k++] = tmp[0]; out[k++] = tmp[1]; out[k] = normalizePressure(predicted.pressure, true)
        predicted.recycle()
        predictedPts = out
    }

    /** Hold-to-snap: called when the pen rested for [autoShapeHoldMs] during a stroke. */
    private fun trySnapCurrentStroke() {
        if (drawingPointerId == -1 || erasing || strokeConsumed || shapeStart != null || !autoShape) return
        if (SystemClock.uptimeMillis() - lastMoveTime < autoShapeHoldMs - 20) return
        val pts = curPts.toArray()
        if (InkGeometry.length(pts) < 12f) return
        val result = ShapeRecognizer.recognize(pts) ?: return
        val strokes = result.polylines().map { toStroke(it) }
        if (strokes.isEmpty()) return
        var doc = ink
        strokes.forEach { doc = doc.addStroke(drawingPage, it) }
        ink = doc
        undo.record(UndoManager.Op.Add(drawingPage, strokes))
        strokeConsumed = true
        curPts.clear(); predictedPts = FloatArray(0)
        performHapticFeedback(
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
            else HapticFeedbackConstants.LONG_PRESS
        )
        listener?.onShapeSnapped()
        emitInkChanged()
        invalidate()
    }

    private fun toStroke(xy: FloatArray): Stroke = Stroke(
        id = UUID.randomUUID().toString(), tool = drawingMode, color = curColor, width = curWidth,
        t = System.currentTimeMillis(), pts = ShapeBuilder.toStrokePoints(xy, if (drawingMode == ToolType.PEN) 0.6f else 0.5f)
    )

    private fun normalizePressure(p: Float, stylus: Boolean): Float {
        if (!stylus) return 0.55f
        return if (p <= 0.001f) 0.5f else p.coerceIn(0.02f, 1f)
    }

    private val tmpPt = FloatArray(2)

    private fun addPoint(sx: Float, sy: Float, pressure: Float) {
        if (drawingPage < 0) return
        screenToPage(drawingPage, sx, sy, tmpPt)
        val x = tmpPt[0]; val y = tmpPt[1]
        if (erasing) {
            eraserCursor = floatArrayOf(sx, sy, eraserRadius * baseScale * zoom)
            eraseAt(lastEraseX, lastEraseY, x, y)
            lastEraseX = x; lastEraseY = y
            return
        }
        val n = curPts.size / 3
        if (n > 0) {
            val lx = curPts[(n - 1) * 3]; val ly = curPts[(n - 1) * 3 + 1]
            val d = hypot(x - lx, y - ly)
            if (d < MIN_POINT_DISTANCE) return
            if (d > HOLD_MOVE_TOLERANCE) {
                lastMoveTime = SystemClock.uptimeMillis()
                if (autoShape) { removeCallbacks(holdRunnable); postDelayed(holdRunnable, autoShapeHoldMs) }
            }
        }
        curPts.add(x, y, normalizePressure(pressure, drawingIsStylus))
    }

    private fun eraseAt(ax: Float, ay: Float, bx: Float, by: Float) {
        val page = ink.page(drawingPage) ?: return
        val hit = page.strokes.filter { InkGeometry.hitsSegment(it, ax, ay, bx, by, eraserRadius) }
        if (hit.isEmpty()) return
        erasedThisGesture += hit
        ink = ink.removeStrokes(drawingPage, hit.map { it.id })
        hit.forEach { pathCache.remove(it.id) }
        invalidate()
    }

    private fun finishStroke() {
        if (drawingPointerId == -1) return
        removeCallbacks(holdRunnable)
        val page = drawingPage
        val start = shapeStart
        if (strokeConsumed) {
            // Already committed by hold-to-snap.
        } else if (start != null) {
            val end = shapeEnd ?: start
            if (hypot(end[0] - start[0], end[1] - start[1]) >= 2f) {
                val strokes = ShapeBuilder.build(shapeKind, start[0], start[1], end[0], end[1], shapeConstrain).map { toStroke(it) }
                var doc = ink
                strokes.forEach { doc = doc.addStroke(page, it) }
                ink = doc
                undo.record(UndoManager.Op.Add(page, strokes))
                emitInkChanged()
            }
        } else if (erasing) {
            if (erasedThisGesture.isNotEmpty()) {
                undo.record(UndoManager.Op.Erase(page, erasedThisGesture.toList()))
                emitInkChanged()
            }
        } else if (curPts.size >= 3) {
            val stroke = Stroke(
                id = UUID.randomUUID().toString(), tool = drawingMode, color = curColor, width = curWidth,
                t = System.currentTimeMillis(), pts = curPts.toArray()
            )
            ink = ink.addStroke(page, stroke)
            undo.record(UndoManager.Op.Add(page, listOf(stroke)))
            emitInkChanged()
        }
        drawingPointerId = -1; drawingPage = -1; erasing = false; strokeConsumed = false
        curPts.clear(); erasedThisGesture.clear(); predictedPts = FloatArray(0)
        shapeStart = null; shapeEnd = null
        if (tool != Tool.ERASER) eraserCursor = null
        invalidate()
    }

    private fun finishStrokeIfAny() { if (drawingPointerId != -1) finishStroke() }

    private fun cancelStroke() {
        removeCallbacks(holdRunnable)
        drawingPointerId = -1; drawingPage = -1; erasing = false; strokeConsumed = false
        curPts.clear(); erasedThisGesture.clear(); eraserCursor = null; predictedPts = FloatArray(0)
        shapeStart = null; shapeEnd = null
        invalidate()
    }

    private fun emitInkChanged() { listener?.onInkChanged(ink, undo.canUndo, undo.canRedo) }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(patchRunnable); removeCallbacks(viewportRunnable); removeCallbacks(holdRunnable)
    }

    /** Growable float list to avoid boxing in the hot input path. */
    private class FloatList {
        private var data = FloatArray(3 * 256)
        var size = 0; private set
        operator fun get(i: Int) = data[i]
        fun add(x: Float, y: Float, p: Float) {
            if (size + 3 > data.size) data = data.copyOf(data.size * 2)
            data[size++] = x; data[size++] = y; data[size++] = p
        }
        fun clear() { size = 0 }
        fun toArray(): FloatArray = data.copyOf(size)
    }

    companion object {
        private const val PALM_WINDOW_MS = 350L
        private const val MIN_POINT_DISTANCE = 0.25f
        private const val HOLD_MOVE_TOLERANCE = 1.5f
        private const val HL_ALPHA = 110
    }
}
