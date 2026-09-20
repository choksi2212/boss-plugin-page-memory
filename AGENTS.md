# AGENTS.md

This file describes the layout and design decisions for the Page Memory plugin.

## Module structure

- `src/main/kotlin/ai/rever/boss/plugin/dynamic/pagemem/` - all sources
- `src/main/resources/META-INF/boss-plugin/plugin.json` - plugin manifest
- `settings.gradle.kts`, `build.gradle.kts` - Gradle setup, mirrors
  `boss-plugin-page-content` and `boss-plugin-git-status`

## Files

- `PageMemoryDynamicPlugin.kt` - the `DynamicPlugin` entry point. Owns the
  store, the watcher and the MCP provider; registers them in `register()` and
  tears them down in `dispose()`.
- `PageMemoryInfo.kt` - `PanelInfo` for the panel: `id = PanelId("page-memory", 68)`,
  `defaultSlotPosition = Panel.left.bottom`.
- `PageMemoryComponent.kt` - `PanelComponentWithUI` that wraps a
  `PageMemoryViewModel`.
- `PageMemoryViewModel.kt` - all UI state (`StateFlow`s) and the imperative
  methods that mutate the store on user actions.
- `PageMemoryContent.kt` - the Compose UI.
- `PageRecord.kt` - the data model: `PageRecord`, `RelatedUrl`, `CrossRef`,
  and the `Relationship` constants (`see-also`, `caused-by`, `blocks`,
  `supersedes`).
- `PageMemoryStore.kt` - persistence. Uses `PluginStorageProvider` with a
  single index key (`MEMORY_INDEX`) and one record per URL
  (`MEMORY_RECORD_<urlHash>`). Mutex-guarded. Refuses to grow past
  `MAX_RECORDS` / `MAX_RECORD_BYTES` / `MAX_VALUE_BYTES`.
- `UrlCanonicalizer.kt` - URL canonicalisation and SHA-256 hashing.
- `ActiveTabWatcher.kt` - subscribes to `applicationEventBus.tabEvents()` and
  bumps `lastSeenAt` for every browser-tab URL change.
- `PageMemoryMcpTools.kt` - the nine `page_memory_*` MCP tools.

## Shared state

The panel and the MCP tools hit the same `PageMemoryStore` instance, so an MCP
write and a panel click on the same URL race through the same mutex. The
`ActiveTabWatcher` runs in its own scope; it has no effect on the panel until
the panel calls `refreshCurrent()` (which the user does via the refresh
button), so a recorded visit never causes the panel UI to recompose mid-typing.

## Why no parent-first API

This plugin only consumes the api through `compileOnly`. The host's parent-first
classloader serves the api types at runtime, so the published jar carries no
`ai.rever.boss.plugin.api` classes. The `boss-plugin-api` version pinned in
`build.gradle.kts` (1.0.93) is the version the plugin compiles against locally.

## Bounded store

Three knobs, all in `PageMemoryStore.kt`:
- `MAX_RECORDS = 50_000` - oldest by `lastSeenAt` is evicted when a new
  record would push the count past the cap. Eviction only fires on writes;
  reads never evict.
- `MAX_RECORD_BYTES = 256 * 1024` - the serialised record size. Anything
  larger is refused at `putRecord`.
- `MAX_VALUE_BYTES = 32 * 1024` - per-field cap on free-text values like
  notes, tags and related URLs. The caller is responsible for the check before
  constructing the record; the store applies the record-level cap as a
  second line of defence.
- `MAX_SEARCH_RESULTS = 200` - `search()` truncates with a flag in its JSON
  response, so callers can paginate by their own key.

## No parent class is `Plugin`; this is a `DynamicPlugin`

`PageMemoryDynamicPlugin` is a `DynamicPlugin`, loaded from a jar the host
finds at runtime. The manifest's `mainClass` points at it; the jar's
`Main-Class` attribute matches.
