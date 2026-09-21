package ai.rever.boss.plugin.dynamic.pagemem

import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.PluginStorageFactory

/**
 * Page Memory dynamic plugin - Loaded from external JAR.
 *
 * Two surfaces, one store:
 *  - the sidebar panel, for human use
 *  - the `page_memory_*` MCP tools, for in-terminal agents
 *
 * Both reach into the same [PageMemoryStore], so an MCP write and a panel
 * click on the same URL race through the same mutex. An optional
 * [ActiveTabWatcher] listens to the application event bus and bumps
 * `lastSeenAt` for every browser tab the user lands on, so a record exists
 * by the time anyone reads it.
 */
class PageMemoryDynamicPlugin : DynamicPlugin {
    override val pluginId: String = "ai.rever.boss.plugin.dynamic.pagemem"
    override val displayName: String = "Page Memory"
    override val version: String = manifestVersion()
    override val description: String =
        "Persistent, URL-keyed memory for pages the user reads in BOSS - notes, tags, related URLs, and cross-plugin references"
    override val author: String = "choksi2212"
    override val url: String = "https://github.com/choksi2212/boss-plugin-page-memory"

    private var store: PageMemoryStore? = null
    private var watcher: ActiveTabWatcher? = null
    private var storageFactory: PluginStorageFactory? = null

    override fun register(context: PluginContext) {
        storageFactory = context.pluginStorageFactory
        val storage = storageFactory?.createStorage(pluginId)
        val resolvedStore = PageMemoryStore(storage)
        store = resolvedStore

        context.panelRegistry.registerPanel(PageMemoryInfo) { ctx, panelInfo ->
            PageMemoryComponent(
                ctx = ctx,
                panelInfo = panelInfo,
                store = resolvedStore,
                activeTabsProvider = context.activeTabsProvider,
            )
        }

        context.registerMcpToolProvider(
            PageMemoryMcpToolProvider(
                providerId = pluginId,
                store = resolvedStore,
                activeTabsProvider = context.activeTabsProvider,
            ),
        )

        val resolvedWatcher = ActiveTabWatcher(
            activeTabsProvider = context.activeTabsProvider,
            eventBus = context.applicationEventBus,
            store = resolvedStore,
        )
        resolvedWatcher.start()
        watcher = resolvedWatcher
    }

    override fun dispose() {
        watcher?.stop()
        watcher = null
        store = null
        storageFactory = null
    }

    /**
     * The version from this plugin's own manifest.
     *
     * Every BOSS plugin ships `/META-INF/boss-plugin/plugin.json` at the
     * same resource path, so a `getResourceAsStream` that returns the first
     * hit could read someone else's manifest if the host ever loads
     * plugins through a parent-first classloader. Only the entry that
     * names this plugin id is accepted.
     */
    private fun manifestVersion(): String =
        runCatching {
            javaClass.classLoader
                ?.getResources("META-INF/boss-plugin/plugin.json")
                ?.asSequence()
                ?.mapNotNull { url -> runCatching { url.readText() }.getOrNull() }
                ?.firstOrNull { text -> field(text, "pluginId") == pluginId }
                ?.let { text -> field(text, "version") }
        }.getOrNull() ?: "unknown"

    private fun field(manifest: String, name: String): String? =
        Regex(""""$name"\s*:\s*"([^"]+)"""").find(manifest)?.groupValues?.get(1)
}
