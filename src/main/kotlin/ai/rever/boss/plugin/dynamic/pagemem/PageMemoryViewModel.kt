package ai.rever.boss.plugin.dynamic.pagemem

import ai.rever.boss.plugin.api.ActiveTabsProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for the Page Memory panel.
 *
 * Holds:
 *  - the record the panel is currently displaying ([currentRecord])
 *  - the URL the panel is keyed on ([currentUrl])
 *  - the user-editable text buffers ([userNotesDraft], [newTagDraft],
 *    [newRelatedUrlDraft], [newRelationshipDraft])
 *  - transient status / error lines
 *
 * The ViewModel is the single place that talks to [PageMemoryStore]; the
 * compose layer is a passive renderer that calls [loadForUrl] /
 * [saveNotes] / etc. on user actions.
 */
class PageMemoryViewModel(
    private val store: PageMemoryStore,
    private val activeTabsProvider: ActiveTabsProvider?,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _currentUrl = MutableStateFlow<String?>(null)
    val currentUrl: StateFlow<String?> = _currentUrl.asStateFlow()

    private val _currentRecord = MutableStateFlow<PageRecord?>(null)
    val currentRecord: StateFlow<PageRecord?> = _currentRecord.asStateFlow()

    private val _recentRecords = MutableStateFlow<List<PageRecord>>(emptyList())
    val recentRecords: StateFlow<List<PageRecord>> = _recentRecords.asStateFlow()

    private val _userNotesDraft = MutableStateFlow("")
    val userNotesDraft: StateFlow<String> = _userNotesDraft.asStateFlow()

    private val _newTagDraft = MutableStateFlow("")
    val newTagDraft: StateFlow<String> = _newTagDraft.asStateFlow()

    private val _newRelatedUrlDraft = MutableStateFlow("")
    val newRelatedUrlDraft: StateFlow<String> = _newRelatedUrlDraft.asStateFlow()

    private val _newRelationshipDraft = MutableStateFlow(Relationship.SEE_ALSO)
    val newRelationshipDraft: StateFlow<String> = _newRelationshipDraft.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<PageRecord>>(emptyList())
    val searchResults: StateFlow<List<PageRecord>> = _searchResults.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun setCurrentUrl(url: String?) {
        val cleaned = url?.takeIf { it.isNotBlank() }
        if (cleaned == _currentUrl.value) return
        _currentUrl.value = cleaned
        if (cleaned != null) {
            val hash = UrlCanonicalizer.hash(cleaned)
            scope.launch {
                val record = store.getRecord(hash)
                _currentRecord.value = record
                _userNotesDraft.value = record?.userNotes ?: ""
            }
        } else {
            _currentRecord.value = null
            _userNotesDraft.value = ""
        }
    }

    /**
     * Refresh the current record from disk.
     *
     * Called after the user edits a field - the in-memory record may be
     * stale relative to what was just written, and a refresh guarantees the
     * panel is showing what is actually on disk.
     */
    fun refreshCurrent() {
        val url = _currentUrl.value ?: return
        val hash = UrlCanonicalizer.hash(url)
        scope.launch {
            _currentRecord.value = store.getRecord(hash)
            refreshRecent()
        }
    }

    fun refreshRecent() {
        scope.launch {
            _recentRecords.value = store.allRecords().take(50)
        }
    }

    fun updateUserNotesDraft(text: String) {
        _userNotesDraft.value = text
    }

    fun saveUserNotes() {
        val url = _currentUrl.value ?: run {
            _error.value = "No page selected"
            return
        }
        if (_userNotesDraft.value.toByteArray(Charsets.UTF_8).size > PageMemoryStore.MAX_VALUE_BYTES) {
            _error.value = "Notes too large (over 32 KiB)"
            return
        }
        val hash = UrlCanonicalizer.hash(url)
        scope.launch {
            val record = store.getRecord(hash)
                ?: PageRecord(
                    urlHash = hash,
                    url = UrlCanonicalizer.canonicalize(url),
                    firstSeenAt = System.currentTimeMillis(),
                    lastSeenAt = System.currentTimeMillis(),
                    visitCount = 1,
                )
            val updated = record.copy(userNotes = _userNotesDraft.value)
            when (val result = store.putRecord(updated)) {
                PageMemoryStore.StoreResult.Ok -> {
                    _currentRecord.value = updated
                    _status.value = "Notes saved"
                    refreshRecent()
                }
                is PageMemoryStore.StoreResult.TooLarge -> _error.value = "Record too large to save"
                PageMemoryStore.StoreResult.Unavailable -> _error.value = "Storage unavailable"
            }
        }
    }

    fun updateNewTagDraft(text: String) {
        _newTagDraft.value = text
    }

    fun addTag() {
        val tag = _newTagDraft.value.trim()
        if (tag.isEmpty()) {
            _error.value = "Tag cannot be empty"
            return
        }
        if (tag.toByteArray(Charsets.UTF_8).size > PageMemoryStore.MAX_VALUE_BYTES) {
            _error.value = "Tag too large"
            return
        }
        val url = _currentUrl.value ?: run {
            _error.value = "No page selected"
            return
        }
        val hash = UrlCanonicalizer.hash(url)
        scope.launch {
            val record = store.getRecord(hash)
                ?: PageRecord(
                    urlHash = hash,
                    url = UrlCanonicalizer.canonicalize(url),
                    firstSeenAt = System.currentTimeMillis(),
                    lastSeenAt = System.currentTimeMillis(),
                    visitCount = 1,
                )
            if (tag in record.tags) {
                _status.value = "Tag already present"
                return@launch
            }
            val updated = record.copy(tags = record.tags + tag)
            when (store.putRecord(updated)) {
                PageMemoryStore.StoreResult.Ok -> {
                    _currentRecord.value = updated
                    _newTagDraft.value = ""
                    _status.value = "Tag added"
                    refreshRecent()
                }
                is PageMemoryStore.StoreResult.TooLarge -> _error.value = "Record too large"
                PageMemoryStore.StoreResult.Unavailable -> _error.value = "Storage unavailable"
            }
        }
    }

    fun removeTag(tag: String) {
        val url = _currentUrl.value ?: return
        val hash = UrlCanonicalizer.hash(url)
        scope.launch {
            val record = store.getRecord(hash) ?: return@launch
            if (tag !in record.tags) return@launch
            val updated = record.copy(tags = record.tags - tag)
            when (store.putRecord(updated)) {
                PageMemoryStore.StoreResult.Ok -> {
                    _currentRecord.value = updated
                    _status.value = "Tag removed"
                    refreshRecent()
                }
                is PageMemoryStore.StoreResult.TooLarge -> _error.value = "Record too large"
                PageMemoryStore.StoreResult.Unavailable -> _error.value = "Storage unavailable"
            }
        }
    }

    fun updateNewRelatedUrlDraft(text: String) {
        _newRelatedUrlDraft.value = text
    }

    fun updateNewRelationshipDraft(value: String) {
        if (value in Relationship.ALL) _newRelationshipDraft.value = value
    }

    fun addRelatedUrl() {
        val relatedUrl = _newRelatedUrlDraft.value.trim()
        if (relatedUrl.isEmpty()) {
            _error.value = "URL cannot be empty"
            return
        }
        if (relatedUrl.toByteArray(Charsets.UTF_8).size > PageMemoryStore.MAX_VALUE_BYTES) {
            _error.value = "URL too large"
            return
        }
        val relationship = _newRelationshipDraft.value
        val url = _currentUrl.value ?: run {
            _error.value = "No page selected"
            return
        }
        val hash = UrlCanonicalizer.hash(url)
        scope.launch {
            val record = store.getRecord(hash)
                ?: PageRecord(
                    urlHash = hash,
                    url = UrlCanonicalizer.canonicalize(url),
                    firstSeenAt = System.currentTimeMillis(),
                    lastSeenAt = System.currentTimeMillis(),
                    visitCount = 1,
                )
            val updated = record.copy(
                relatedUrls = record.relatedUrls + RelatedUrl(relatedUrl, relationship),
            )
            when (store.putRecord(updated)) {
                PageMemoryStore.StoreResult.Ok -> {
                    _currentRecord.value = updated
                    _newRelatedUrlDraft.value = ""
                    _status.value = "Related URL added"
                    refreshRecent()
                }
                is PageMemoryStore.StoreResult.TooLarge -> _error.value = "Record too large"
                PageMemoryStore.StoreResult.Unavailable -> _error.value = "Storage unavailable"
            }
        }
    }

    fun removeRelatedUrl(index: Int) {
        val url = _currentUrl.value ?: return
        val hash = UrlCanonicalizer.hash(url)
        scope.launch {
            val record = store.getRecord(hash) ?: return@launch
            if (index !in record.relatedUrls.indices) return@launch
            val updated = record.copy(
                relatedUrls = record.relatedUrls.toMutableList().apply { removeAt(index) },
            )
            when (store.putRecord(updated)) {
                PageMemoryStore.StoreResult.Ok -> {
                    _currentRecord.value = updated
                    _status.value = "Removed"
                    refreshRecent()
                }
                is PageMemoryStore.StoreResult.TooLarge -> _error.value = "Record too large"
                PageMemoryStore.StoreResult.Unavailable -> _error.value = "Storage unavailable"
            }
        }
    }

    fun updateSearchQuery(text: String) {
        _searchQuery.value = text
        scope.launch {
            _searchResults.value = store.search(text)
        }
    }

    fun clearMessages() {
        _status.value = null
        _error.value = null
    }

    fun clearCurrent() {
        _currentUrl.value = null
        _currentRecord.value = null
        _userNotesDraft.value = ""
    }

    /**
     * Pull the URL of the active browser tab, if any.
     *
     * Returns null on hosts without the browser plugin; callers should fall
     * back to "no page" state and let the user paste a URL.
     */
    fun currentBrowserUrl(): String? {
        val provider = activeTabsProvider ?: return null
        return runCatching {
            val activePanel = provider.activePanelId
            val tabs = provider.activeTabs.value
            val candidate = if (activePanel != null) {
                tabs.firstOrNull { it.panelId == activePanel && !it.url.isNullOrBlank() }
            } else {
                null
            } ?: tabs.firstOrNull { !it.url.isNullOrBlank() }
            candidate?.url
        }.getOrNull()
    }
}
