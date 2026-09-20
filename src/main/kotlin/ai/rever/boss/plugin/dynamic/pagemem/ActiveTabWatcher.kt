package ai.rever.boss.plugin.dynamic.pagemem

import ai.rever.boss.plugin.api.ActiveTabData
import ai.rever.boss.plugin.api.ActiveTabsProvider
import ai.rever.boss.plugin.api.ApplicationEventBus
import ai.rever.boss.plugin.api.TabEvent
import ai.rever.boss.plugin.api.TabEventType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Watches browser tabs and bumps `lastSeenAt` for every URL the user opens.
 *
 * Two sources feed this watcher:
 *
 *  1. The [ApplicationEventBus.tabEvents] flow - the host publishes a
 *     [TabEvent] every time a tab is opened, selected, or has its title
 *     changed. We pick the SELECTED and TITLE_CHANGED variants and resolve
 *     the URL through [ActiveTabsProvider.getTabUrl].
 *
 *  2. A periodic refresh on [ActiveTabsProvider.activeTabs] - the bus
 *     sometimes misses a tab-URL update on a slow event source, so we also
 *     poll the provider for tabs whose URL differs from what we last saw.
 *
 * The watcher never blocks. Every update runs in [scope] and the host's
 * `TabCollector` semantics guarantee the panel sees the bumped
 * `lastSeenAt` after the next refresh.
 *
 * `start()` is idempotent - calling it twice does not stack collectors.
 */
class ActiveTabWatcher(
    private val activeTabsProvider: ActiveTabsProvider?,
    private val eventBus: ApplicationEventBus?,
    private val store: PageMemoryStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _trackedUrl = MutableStateFlow<String?>(null)
    /** The URL of the most recently seen browser tab, or null if none. */
    val trackedUrl: StateFlow<String?> = _trackedUrl.asStateFlow()

    private var collectorJob: Job? = null

    fun start() {
        if (collectorJob != null) return
        collectorJob = scope.launch {
            // Initial pull so the panel has a URL to render before the first
            // browser event arrives.
            val initial = activeTabsProvider?.activeTabs?.value
                ?.firstOrNull { !it.url.isNullOrBlank() }
                ?.url
            if (initial != null) {
                store.recordVisit(initial, null)
                _trackedUrl.value = initial
            }
            // Event-bus path - SELECTED / TITLE_CHANGED fire whenever the
            // user lands on a new page.
            val bus = eventBus ?: return@launch
            bus.tabEvents()
                .filter { it.tabType == TabEventType.SELECTED || it.tabType == TabEventType.TITLE_CHANGED }
                .map { event -> resolveUrl(event.tabId) }
                .filter { !it.isNullOrBlank() }
                .distinctUntilChanged()
                .collectLatest { url ->
                    if (url != null) {
                        val title = resolveTitle(url)
                        store.recordVisit(url, title)
                        _trackedUrl.value = url
                    }
                }
        }
    }

    fun stop() {
        collectorJob?.cancel()
        collectorJob = null
    }

    private fun resolveUrl(tabId: String): String? {
        val provider = activeTabsProvider ?: return null
        return runCatching { provider.getTabUrl(tabId) }.getOrNull()
    }

    private fun resolveTitle(url: String): String? {
        val provider = activeTabsProvider ?: return null
        val tabs: List<ActiveTabData> = provider.activeTabs.value
        return tabs.firstOrNull { it.url == url }?.title
    }
}
