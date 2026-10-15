# NotesPlus

An Android note-taking app for annotating lecture notes with a stylus, with two-way
OneDrive sync that never loses an annotation.

* **Stylus-first inking** – pressure-sensitive pen, highlighter (multiply blend), stroke
  eraser, undo/redo, palm rejection, side-button-to-erase, finger pan/pinch-zoom,
  double-tap zoom, mouse-wheel scrolling on Chromebooks.
* **PDF lecture notes** – open any PDF (import from the device, "Open with" from another
  app, or simply drop it in your OneDrive folder). Pages render crisply at any zoom.
* **Blank notebooks** – ruled / grid / dotted / plain paper, add pages as you go.
* **Automatic OneDrive sync** – pick a folder on OneDrive; every PDF in it appears in the
  app, your annotations are uploaded a few seconds after you stop writing, and changes made
  elsewhere are pulled in (on app start, every 15 minutes in the background, and on demand).
* **Annotations are never lost** – ink is stored as a small `*.ink.json` sidecar next to each
  PDF. Strokes are immutable and identified by id, erasures are tombstones, so two devices
  editing the same note offline merge cleanly instead of overwriting each other.
* **Export** – "Export annotated PDF" writes your ink into a copy of the PDF as real vector
  content (sharp, small, opens anywhere). Optionally uploaded automatically on every sync.

## Building

Requirements: JDK 17+, Android SDK 35 (the Gradle wrapper fetches everything else).

```bash
./gradlew :app:assembleDebug        # APK in app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest    # merge / geometry / undo unit tests
```

Install with `adb install app/build/outputs/apk/debug/app-debug.apk`.

## OneDrive setup (one-time, ~3 minutes)

The app signs in with the Microsoft identity platform using the standard OAuth
authorization-code + PKCE flow. There is no secret to protect, but Microsoft requires every
app to have its own *client ID*, so you register one (free, works with personal accounts):

1. Go to <https://portal.azure.com> → **Microsoft Entra ID** → **App registrations** → **New registration**.
2. Name: `NotesPlus`. Supported account types: **Personal Microsoft accounts only** (or
   "Accounts in any organizational directory and personal Microsoft accounts" if you also
   want a university account).
3. Redirect URI: choose platform **Mobile and desktop applications** and enter
   `notesplus://auth`
4. Register, then copy the **Application (client) ID**.
5. Under **Authentication** make sure *Allow public client flows* is **Yes**.
6. (API permissions) `User.Read`, `Files.ReadWrite` and `offline_access` are requested at
   sign-in as delegated permissions; the default *User.Read* is already there and the
   others are granted when you consent on first sign-in.

Then either paste the client ID into **Settings → Azure app (client) ID** inside the app,
or bake it into the build by adding to `local.properties`:

```
NOTESPLUS_CLIENT_ID=xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
```

Finally **Settings → Sign in**, then **OneDrive folder → Change** to choose the folder that
holds your lecture notes (default `/NotesPlus`, created for you).

## How sync works

```
OneDrive/<folder>/
  Week 1/Lecture 3.pdf            ← your original PDF, never modified
  Week 1/Lecture 3.pdf.ink.json   ← your annotations (strokes in PDF points, per page)
  Scratch.ink.json                ← a blank notebook created in the app
  Lecture 3.annotated.pdf         ← optional flattened export
```

* **Pull** uses the Graph *delta* API on the folder, so only changed files are touched.
  New PDFs are downloaded; changed sidecars are merged into the local annotations.
* **Push** uploads dirty notes. Sidecar uploads use `If-Match` on the remote eTag; on a
  `412 Precondition Failed` the remote copy is merged in first and the upload retried, so
  concurrent edits from two devices are combined rather than lost.
* Deleting a note in the app offers to delete it on OneDrive too; deleting a PDF on
  OneDrive removes it from the device unless it has unsynced local edits.
* Subfolders are supported and shown as part of the note's subtitle.

## Project layout

```
app/src/main/java/com/notesplus/app/
  ink/        InkModel (format + merge), InkGeometry (pressure outlines, eraser hit-tests)
  editor/     DocumentView (stylus input, zoom/scroll, drawing), PageSource, PageRenderer, UndoManager
  data/       Room database, NoteRepository (files + per-note locking), Prefs (DataStore)
  onedrive/   AuthManager (PKCE), GraphClient, SyncEngine, SyncWorker/SyncScheduler
  export/     PdfExporter (vector export via PdfBox-Android)
  ui/         Compose screens: library, editor, settings
```
