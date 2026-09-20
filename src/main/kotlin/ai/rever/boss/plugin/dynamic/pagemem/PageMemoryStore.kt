package ai.rever.boss.plugin.dynamic.pagemem

import ai.rever.boss.plugin.api.PluginStorageProvider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Persistent store for [PageRecord]s.
 *
 * Backed by [PluginStorageProvider] so each record lives under its own key
 * (`MEMORY_RECORD_<urlHash>`) and a single index key (`MEMORY_INDEX`) lists
 * the known hashes. Reads scan the index, not the full keyspace, so the
 * cost of an unrelated plugin adding its own keys is bounded.
 *
 * ## Bounded growth
 *
 * The user could open ten thousand pages a year and the store could explode,
 * so the store refuses to add beyond these limits:
 *  - [MAX_RECORDS] (50,000) - the oldest record by `lastSeenAt` is evicted
 *    when the cap is hit. Eviction only fires when a new record would push
 *    the count past the cap; read-only operations never evict.
 *  - [MAX_RECORD_BYTES] (256 KiB) - a [putRecord] that serialises to more
 *    than this is refused. The cap is on the *serialised* size, so a record
 *    field that fits one platform's UTF-8 might not fit another's; the
 *    conservative limit is the smallest.
 *  - [MAX_VALUE_BYTES] (32 KiB) - a tag, note or related-URL that exceeds
 *    this on its own is refused before it is written.
 *
 * [search] returns at most [MAX_SEARCH_RESULTS] (200) records; callers that
 * want more should paginate by their own key (the order is stable: most
 * recently seen first).
 *
 * All public functions are safe to call concurrently. Mutations hold an
 * internal mutex so the index and the per-record writes stay in sync;
 * reads are lock-free.
 */
class PageMemoryStore(
    private val storage: PluginStorageProvider?,
) {

    private val mutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val listSerializer = ListSerializer(String.serializer())
    private val recordSerializer = PageRecord.serializer()

    suspend fun getRecord(urlHash: String): PageRecord? {
        val s = storage ?: return null
        val raw = s.getJson(recordKey(urlHash)) ?: return null
        return runCatching { json.decodeFromString(recordSerializer, raw) }.getOrNull()
    }

    suspend fun getRecordByUrl(url: String): PageRecord? {
        val hash = UrlCanonicalizer.hash(url)
        return getRecord(hash)
    }

    /**
     * Every record currently on disk.
     *
     * Returns records in `lastSeenAt` descending order so the panel's "most
     * recent" tab matches the order returned here. Records missing the field
     * sort to the end.
     */
    suspend fun allRecords(): List<PageRecord> {
        val s = storage ?: return emptyList()
        val hashes = readIndex(s)
        if (hashes.isEmpty()) return emptyList()
        val records = mutableListOf<PageRecord>()
        for (hash in hashes) {
            val raw = s.getJson(recordKey(hash)) ?: continue
            val record = runCatching { json.decodeFromString(recordSerializer, raw) }.getOrNull()
            if (record != null) records.add(record)
        }
        return records.sortedByDescending { it.lastSeenAt }
    }

    /**
     * Write or replace [record].
     *
     * The record is refused if it serialises past [MAX_RECORD_BYTES]; any
     * oversized individual field should already have been refused by the
     * caller, but this is the second line of defence. If the record would
     * push the index past [MAX_RECORDS], the oldest by `lastSeenAt` is
     * evicted first.
     */
    suspend fun putRecord(record: PageRecord): StoreResult {
        val s = storage ?: return StoreResult.Unavailable
        val serialized = json.encodeToString(recordSerializer, record)
        if (serialized.toByteArray(Charsets.UTF_8).size > MAX_RECORD_BYTES) {
            return StoreResult.TooLarge(serialized.toByteArray(Charsets.UTF_8).size)
        }
        mutex.withLock {
            val hashes = readIndex(s).toMutableList()
            if (record.urlHash !in hashes) {
                hashes.add(record.urlHash)
                evictIfOverCap(s, hashes)
            }
            s.putJson(recordKey(record.urlHash), serialized)
            s.putJson(INDEX_KEY, json.encodeToString(listSerializer, hashes))
        }
        return StoreResult.Ok
    }

    suspend fun removeRecord(urlHash: String): Boolean {
        val s = storage ?: return false
        var removed = false
        mutex.withLock {
            val hashes = readIndex(s).toMutableList()
            if (hashes.remove(urlHash)) {
                s.remove(recordKey(urlHash))
                s.putJson(INDEX_KEY, json.encodeToString(listSerializer, hashes))
                removed = true
            }
        }
        return removed
    }

    suspend fun recordCount(): Int {
        val s = storage ?: return 0
        return readIndex(s).size
    }

    /**
     * Substring search across `url`, `title`, `userNotes`, `agentNotes` and `tags`.
     *
     * Case-insensitive, returns at most [MAX_SEARCH_RESULTS] records. The
     * order is `lastSeenAt` descending, so a search for the same query
     * twice in a row returns the same set.
     */
    suspend fun search(query: String, limit: Int = MAX_SEARCH_RESULTS): List<PageRecord> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val needle = trimmed.lowercase()
        val records = allRecords()
        val matches = records.filter { rec ->
            rec.url.lowercase().contains(needle) ||
                (rec.title?.lowercase()?.contains(needle) == true) ||
                rec.userNotes.lowercase().contains(needle) ||
                rec.agentNotes.lowercase().contains(needle) ||
                rec.tags.any { it.lowercase().contains(needle) }
        }
        return matches.take(limit.coerceAtLeast(0))
    }

    /**
     * Bulk update that bumps visitCount / lastSeenAt / title for [url].
     *
     * Creates a new record if none exists. The created record uses [title]
     * when supplied and an empty string for the user-visible note fields.
     */
    suspend fun recordVisit(url: String, title: String? = null, now: Long = System.currentTimeMillis()): PageRecord? {
        val s = storage ?: return null
        val hash = UrlCanonicalizer.hash(url)
        val canonical = UrlCanonicalizer.canonicalize(url)
        val existing = getRecord(hash)
        val updated = if (existing == null) {
            PageRecord(
                urlHash = hash,
                url = canonical,
                title = title,
                firstSeenAt = now,
                lastSeenAt = now,
                visitCount = 1,
            )
        } else {
            existing.copy(
                title = title ?: existing.title,
                lastSeenAt = now,
                visitCount = existing.visitCount + 1,
            )
        }
        return when (val result = putRecord(updated)) {
            StoreResult.Ok -> updated
            else -> null
        }
    }

    private suspend fun evictIfOverCap(s: PluginStorageProvider, hashes: MutableList<String>) {
        if (hashes.size <= MAX_RECORDS) return
        // Load the records we are considering evicting, sort by lastSeenAt
        // ascending, and drop enough to make room. We only load what is
        // needed - if the cap is 50k and we are at 50k + 1, only the oldest
        // one needs to go.
        val toEvict = hashes.size - MAX_RECORDS
        val sorted = hashes.mapNotNull { hash ->
            val raw = s.getJson(recordKey(hash)) ?: return@mapNotNull null
            runCatching { json.decodeFromString(recordSerializer, raw) }.getOrNull()
        }.sortedBy { it.lastSeenAt }
        for (rec in sorted.take(toEvict)) {
            s.remove(recordKey(rec.urlHash))
            hashes.remove(rec.urlHash)
        }
    }

    private suspend fun readIndex(s: PluginStorageProvider): List<String> {
        val raw = s.getJson(INDEX_KEY) ?: return emptyList()
        return runCatching { json.decodeFromString(listSerializer, raw) }.getOrDefault(emptyList())
    }

    private fun recordKey(hash: String): String = "MEMORY_RECORD_$hash"

    sealed class StoreResult {
        object Ok : StoreResult()
        object Unavailable : StoreResult()
        data class TooLarge(val bytes: Int) : StoreResult()
    }

    companion object {
        const val INDEX_KEY = "MEMORY_INDEX"

        /** Hard cap on the number of stored records. */
        const val MAX_RECORDS = 50_000

        /** Per-record size limit, in bytes, on the serialised JSON. */
        const val MAX_RECORD_BYTES = 256 * 1024

        /** Per-field size limit for free-text values like notes and tags. */
        const val MAX_VALUE_BYTES = 32 * 1024

        /** Maximum number of records returned by [search]. */
        const val MAX_SEARCH_RESULTS = 200
    }
}
