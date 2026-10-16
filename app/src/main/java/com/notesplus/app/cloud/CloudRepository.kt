package com.notesplus.app.cloud

import com.notesplus.app.cloud.auth.OAuthManager
import com.notesplus.app.data.db.CloudDao
import com.notesplus.app.data.db.CloudItemEntity
import com.notesplus.app.data.db.Provider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Cached view of the user's drives. The browser reads folder contents from Room (instant,
 * works offline) while [refresh] fetches fresh listings in the background.
 */
class CloudRepository(
    private val dao: CloudDao,
    val providers: Map<Provider, CloudProvider>,
    val auth: Map<Provider, OAuthManager>
) {
    private val refreshLocks = ConcurrentHashMap<String, Mutex>()

    fun isSignedIn(p: Provider) = auth[p]?.isSignedIn == true
    fun signedInProviders(): List<Provider> = Provider.entries.filter { isSignedIn(it) }

    fun observeChildren(provider: Provider, folderId: String?): Flow<List<CloudItemEntity>> = dao.observeChildren(provider, folderId)
    fun observeItem(provider: Provider, id: String) = dao.observe(provider, id)
    suspend fun item(provider: Provider, id: String): CloudItemEntity? = dao.get(provider, id)

    suspend fun isStale(provider: Provider, folderId: String?, maxAgeMs: Long = STALE_MS): Boolean {
        val st = dao.folderState(provider, folderId ?: "") ?: return true
        return System.currentTimeMillis() - st.listedAt > maxAgeMs
    }

    /**
     * Re-lists [folderId] from the drive and replaces the cache. Returns null on success or
     * a message when the drive could not be reached (the cached listing stays visible).
     */
    suspend fun refresh(provider: Provider, folderId: String?, force: Boolean = true): String? {
        if (!isSignedIn(provider)) return "Not signed in to ${provider.label}"
        if (!force && !isStale(provider, folderId)) return null
        val lock = refreshLocks.getOrPut("$provider/$folderId") { Mutex() }
        if (!lock.tryLock()) return null
        try {
            val cp = providers.getValue(provider)
            val children = cp.listChildren(folderId)
            val now = System.currentTimeMillis()
            val entities = children.map { f ->
                CloudItemEntity(
                    provider = provider, id = f.id, parentId = folderId, name = f.name, isFolder = f.isFolder,
                    size = f.size, modifiedAt = f.modifiedAt, version = f.version, sortKey = f.sortKey, fetchedAt = now
                )
            }
            dao.replaceChildren(provider, folderId, entities)
            return null
        } catch (e: Exception) {
            return e.message ?: e.javaClass.simpleName
        } finally {
            lock.unlock()
        }
    }

    /** "My Drive / COMP1234 / Week 1" built from the cache (falls back to what is known). */
    suspend fun pathLabel(provider: Provider, folderId: String?): String {
        val parts = ArrayList<String>()
        var cur = folderId
        var guard = 0
        while (cur != null && guard++ < 32) {
            val item = dao.get(provider, cur) ?: break
            parts.add(0, item.name)
            cur = item.parentId
        }
        return (listOf(provider.label) + parts).joinToString(" / ")
    }

    /** Breadcrumb entries (root first) for [folderId]. */
    suspend fun breadcrumb(provider: Provider, folderId: String?): List<CloudItemEntity> {
        val out = ArrayList<CloudItemEntity>()
        var cur = folderId
        var guard = 0
        while (cur != null && guard++ < 32) {
            val item = dao.get(provider, cur) ?: break
            out.add(0, item)
            cur = item.parentId
        }
        return out
    }

    suspend fun invalidateCache(provider: Provider) {
        dao.deleteAllFor(provider)
        dao.deleteFolderStatesFor(provider)
    }

    companion object { const val STALE_MS = 10 * 60 * 1000L }
}
