package ai.rever.boss.plugin.dynamic.pagemem

import ai.rever.boss.plugin.api.ActiveTabsProvider
import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * MCP tools contributed by the Page Memory plugin.
 *
 * Nine focused tools that read or mutate the per-URL memory store:
 *  - `page_memory_get`            - fetch the record for a URL.
 *  - `page_memory_get_current`    - same, for the active browser tab.
 *  - `page_memory_set_notes`      - replace the user notes for a URL.
 *  - `page_memory_add_tag`        - append a tag (deduplicated).
 *  - `page_memory_record_visit`   - bump visitCount + lastSeenAt, set title.
 *  - `page_memory_attach`         - other plugins attach their own refs.
 *  - `page_memory_relate`         - add a related-URL edge.
 *  - `page_memory_search`         - substring search across all records.
 *  - `page_memory_export`         - dump every record as JSON or Markdown.
 *
 * All tools share the same [PageMemoryStore] instance the panel uses; an
 * MCP call and a panel click hit the same mutex.
 */
internal class PageMemoryMcpToolProvider(
    override val providerId: String,
    private val store: PageMemoryStore,
    private val activeTabsProvider: ActiveTabsProvider?,
) : McpToolProvider {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun tools(): List<McpToolDefinition> = listOf(
        get(),
        getCurrent(),
        setNotes(),
        addTag(),
        recordVisit(),
        attach(),
        relate(),
        search(),
        export(),
    )

    private fun get(): McpToolDefinition = McpToolDefinition(
        name = "page_memory_get",
        description = "Return the memory record for a URL, or null when no record exists.",
        inputSchema = URL_SCHEMA,
        handler = McpToolHandler { args -> handleGet(args) },
    )

    private fun getCurrent(): McpToolDefinition = McpToolDefinition(
        name = "page_memory_get_current",
        description = "Return the memory record for the URL of the active browser tab, or null.",
        handler = McpToolHandler { _ -> handleGetCurrent() },
    )

    private fun setNotes(): McpToolDefinition = McpToolDefinition(
        name = "page_memory_set_notes",
        description = "Replace the user notes for a URL. Creates the record if needed.",
        inputSchema = SET_NOTES_SCHEMA,
        readOnly = false,
        handler = McpToolHandler { args -> handleSetNotes(args) },
    )

    private fun addTag(): McpToolDefinition = McpToolDefinition(
        name = "page_memory_add_tag",
        description = "Append a tag to a URL's memory record (deduplicated).",
        inputSchema = TAG_SCHEMA,
        readOnly = false,
        handler = McpToolHandler { args -> handleAddTag(args) },
    )

    private fun recordVisit(): McpToolDefinition = McpToolDefinition(
        name = "page_memory_record_visit",
        description = "Bump visitCount and lastSeenAt for a URL; optionally set the title.",
        inputSchema = RECORD_VISIT_SCHEMA,
        readOnly = false,
        handler = McpToolHandler { args -> handleRecordVisit(args) },
    )

    private fun attach(): McpToolDefinition = McpToolDefinition(
        name = "page_memory_attach",
        description = "Attach another plugin's reference (id + type + label) to a page's memory record.",
        inputSchema = ATTACH_SCHEMA,
        readOnly = false,
        handler = McpToolHandler { args -> handleAttach(args) },
    )

    private fun relate(): McpToolDefinition = McpToolDefinition(
        name = "page_memory_relate",
        description = "Add a related-URL edge between two pages (see-also / caused-by / blocks / supersedes).",
        inputSchema = RELATE_SCHEMA,
        readOnly = false,
        handler = McpToolHandler { args -> handleRelate(args) },
    )

    private fun search(): McpToolDefinition = McpToolDefinition(
        name = "page_memory_search",
        description = "Substring search across url, title, userNotes, agentNotes and tags; returns up to 200 records.",
        inputSchema = SEARCH_SCHEMA,
        handler = McpToolHandler { args -> handleSearch(args) },
    )

    private fun export(): McpToolDefinition = McpToolDefinition(
        name = "page_memory_export",
        description = "Export every memory record as JSON or Markdown.",
        inputSchema = EXPORT_SCHEMA,
        handler = McpToolHandler { args -> handleExport(args) },
    )

    private suspend fun handleGet(args: McpToolArgs): McpToolResult {
        val url = args.string("url")?.trim()
            ?: return McpToolResult("Missing required argument: url", isError = true)
        val hash = UrlCanonicalizer.hash(url)
        val record = store.getRecord(hash)
            ?: return McpToolResult(buildJsonObject {
                put("found", false)
                put("urlHash", hash)
                put("url", UrlCanonicalizer.canonicalize(url))
            }.toString())
        return McpToolResult(
            buildJsonObject {
                put("found", true)
                put("record", encodeRecord(record))
            }.toString(),
        )
    }

    private suspend fun handleGetCurrent(): McpToolResult {
        val url = currentBrowserUrl() ?: return McpToolResult(
            "No browser tab with a URL is active.",
            isError = true,
        )
        val hash = UrlCanonicalizer.hash(url)
        val record = store.getRecord(hash)
        return McpToolResult(
            buildJsonObject {
                put("url", UrlCanonicalizer.canonicalize(url))
                put("found", record != null)
                if (record != null) put("record", encodeRecord(record))
            }.toString(),
        )
    }

    private suspend fun handleSetNotes(args: McpToolArgs): McpToolResult {
        val url = args.string("url")?.trim()
            ?: return McpToolResult("Missing required argument: url", isError = true)
        val notes = args.string("notes") ?: ""
        if (notes.toByteArray(Charsets.UTF_8).size > PageMemoryStore.MAX_VALUE_BYTES) {
            return McpToolResult("Notes too large (over 32 KiB)", isError = true)
        }
        val hash = UrlCanonicalizer.hash(url)
        val canonical = UrlCanonicalizer.canonicalize(url)
        val now = System.currentTimeMillis()
        val existing = store.getRecord(hash)
        val updated = (existing ?: PageRecord(
            urlHash = hash,
            url = canonical,
            firstSeenAt = now,
            lastSeenAt = now,
            visitCount = 0,
        )).copy(userNotes = notes)
        return when (store.putRecord(updated)) {
            PageMemoryStore.StoreResult.Ok -> McpToolResult(
                buildJsonObject { put("ok", true); put("record", encodeRecord(updated)) }.toString(),
            )
            is PageMemoryStore.StoreResult.TooLarge -> McpToolResult("Record too large", isError = true)
            PageMemoryStore.StoreResult.Unavailable -> McpToolResult("Storage unavailable", isError = true)
        }
    }

    private suspend fun handleAddTag(args: McpToolArgs): McpToolResult {
        val url = args.string("url")?.trim()
            ?: return McpToolResult("Missing required argument: url", isError = true)
        val tag = args.string("tag")?.trim()
            ?: return McpToolResult("Missing required argument: tag", isError = true)
        if (tag.isEmpty()) {
            return McpToolResult("Tag cannot be empty", isError = true)
        }
        if (tag.toByteArray(Charsets.UTF_8).size > PageMemoryStore.MAX_VALUE_BYTES) {
            return McpToolResult("Tag too large", isError = true)
        }
        val hash = UrlCanonicalizer.hash(url)
        val now = System.currentTimeMillis()
        val canonical = UrlCanonicalizer.canonicalize(url)
        val existing = store.getRecord(hash)
        if (existing != null && tag in existing.tags) {
            return McpToolResult(buildJsonObject {
                put("ok", true)
                put("duplicate", true)
                put("record", encodeRecord(existing))
            }.toString())
        }
        val updated = (existing ?: PageRecord(
            urlHash = hash,
            url = canonical,
            firstSeenAt = now,
            lastSeenAt = now,
            visitCount = 0,
        )).copy(tags = (existing?.tags ?: emptyList()) + tag)
        return when (store.putRecord(updated)) {
            PageMemoryStore.StoreResult.Ok -> McpToolResult(
                buildJsonObject { put("ok", true); put("duplicate", false); put("record", encodeRecord(updated)) }.toString(),
            )
            is PageMemoryStore.StoreResult.TooLarge -> McpToolResult("Record too large", isError = true)
            PageMemoryStore.StoreResult.Unavailable -> McpToolResult("Storage unavailable", isError = true)
        }
    }

    private suspend fun handleRecordVisit(args: McpToolArgs): McpToolResult {
        val url = args.string("url")?.trim()
            ?: return McpToolResult("Missing required argument: url", isError = true)
        val title = args.string("title")?.trim()
        val record = store.recordVisit(url, title)
            ?: return McpToolResult("Storage unavailable", isError = true)
        return McpToolResult(
            buildJsonObject { put("ok", true); put("record", encodeRecord(record)) }.toString(),
        )
    }

    private suspend fun handleAttach(args: McpToolArgs): McpToolResult {
        val url = args.string("url")?.trim()
            ?: return McpToolResult("Missing required argument: url", isError = true)
        val pluginId = args.string("pluginId")?.trim()
            ?: return McpToolResult("Missing required argument: pluginId", isError = true)
        val refId = args.string("refId")?.trim()
            ?: return McpToolResult("Missing required argument: refId", isError = true)
        val refType = args.string("refType")?.trim()
            ?: return McpToolResult("Missing required argument: refType", isError = true)
        val label = args.string("label") ?: ""
        if (pluginId.toByteArray(Charsets.UTF_8).size > PageMemoryStore.MAX_VALUE_BYTES ||
            refId.toByteArray(Charsets.UTF_8).size > PageMemoryStore.MAX_VALUE_BYTES ||
            refType.toByteArray(Charsets.UTF_8).size > PageMemoryStore.MAX_VALUE_BYTES ||
            label.toByteArray(Charsets.UTF_8).size > PageMemoryStore.MAX_VALUE_BYTES
        ) {
            return McpToolResult("One of pluginId / refId / refType / label is too large", isError = true)
        }
        val hash = UrlCanonicalizer.hash(url)
        val canonical = UrlCanonicalizer.canonicalize(url)
        val now = System.currentTimeMillis()
        val existing = store.getRecord(hash)
        val updated = (existing ?: PageRecord(
            urlHash = hash,
            url = canonical,
            firstSeenAt = now,
            lastSeenAt = now,
            visitCount = 0,
        )).copy(crossRefs = (existing?.crossRefs ?: emptyList()) + CrossRef(pluginId, refId, refType, label))
        return when (store.putRecord(updated)) {
            PageMemoryStore.StoreResult.Ok -> McpToolResult(
                buildJsonObject { put("ok", true); put("record", encodeRecord(updated)) }.toString(),
            )
            is PageMemoryStore.StoreResult.TooLarge -> McpToolResult("Record too large", isError = true)
            PageMemoryStore.StoreResult.Unavailable -> McpToolResult("Storage unavailable", isError = true)
        }
    }

    private suspend fun handleRelate(args: McpToolArgs): McpToolResult {
        val urlA = args.string("urlA")?.trim()
            ?: return McpToolResult("Missing required argument: urlA", isError = true)
        val urlB = args.string("urlB")?.trim()
            ?: return McpToolResult("Missing required argument: urlB", isError = true)
        val relationship = args.string("relationship")?.trim()
            ?: return McpToolResult("Missing required argument: relationship", isError = true)
        if (relationship !in Relationship.ALL) {
            return McpToolResult(
                "Unknown relationship '$relationship'; must be one of ${Relationship.ALL.joinToString(", ")}",
                isError = true,
            )
        }
        val hashA = UrlCanonicalizer.hash(urlA)
        val canonicalA = UrlCanonicalizer.canonicalize(urlA)
        val now = System.currentTimeMillis()
        val existing = store.getRecord(hashA)
        val updated = (existing ?: PageRecord(
            urlHash = hashA,
            url = canonicalA,
            firstSeenAt = now,
            lastSeenAt = now,
            visitCount = 0,
        )).copy(relatedUrls = (existing?.relatedUrls ?: emptyList()) + RelatedUrl(urlB, relationship))
        return when (store.putRecord(updated)) {
            PageMemoryStore.StoreResult.Ok -> McpToolResult(
                buildJsonObject { put("ok", true); put("record", encodeRecord(updated)) }.toString(),
            )
            is PageMemoryStore.StoreResult.TooLarge -> McpToolResult("Record too large", isError = true)
            PageMemoryStore.StoreResult.Unavailable -> McpToolResult("Storage unavailable", isError = true)
        }
    }

    private suspend fun handleSearch(args: McpToolArgs): McpToolResult {
        val query = args.string("query")?.trim()
            ?: return McpToolResult("Missing required argument: query", isError = true)
        val results = store.search(query)
        return McpToolResult(
            buildJsonObject {
                put("query", query)
                put("count", results.size)
                put("truncated", results.size >= PageMemoryStore.MAX_SEARCH_RESULTS)
                put("records", buildJsonArray { results.forEach { add(encodeRecord(it)) } })
            }.toString(),
        )
    }

    private suspend fun handleExport(args: McpToolArgs): McpToolResult {
        val format = (args.string("format") ?: "json").lowercase()
        val records = store.allRecords()
        val text = when (format) {
            "markdown", "md" -> renderMarkdown(records)
            else -> json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(PageRecord.serializer()),
                records,
            )
        }
        return McpToolResult(text = text)
    }

    private fun renderMarkdown(records: List<PageRecord>): String {
        if (records.isEmpty()) return "# Page Memory\n\nNo records.\n"
        val sb = StringBuilder()
        sb.append("# Page Memory\n\n")
        sb.append("Records: ").append(records.size).append('\n').append('\n')
        for (rec in records) {
            sb.append("## ").append(rec.title ?: rec.url).append('\n')
            sb.append(rec.url).append('\n')
            if (rec.firstSeenAt > 0L) {
                sb.append("First seen: ").append(formatTime(rec.firstSeenAt)).append(" - ")
            }
            sb.append("Last seen: ")
                .append(if (rec.lastSeenAt > 0L) formatTime(rec.lastSeenAt) else "-")
                .append(" - Visits: ").append(rec.visitCount).append('\n')
            if (rec.tags.isNotEmpty()) {
                sb.append("Tags: ").append(rec.tags.joinToString(", ")).append('\n')
            }
            if (rec.userNotes.isNotBlank()) {
                sb.append('\n').append(rec.userNotes).append('\n')
            }
            if (rec.agentNotes.isNotBlank()) {
                sb.append('\n').append("### Agent notes\n").append(rec.agentNotes).append('\n')
            }
            if (rec.relatedUrls.isNotEmpty()) {
                sb.append('\n').append("### Related\n")
                rec.relatedUrls.forEach { sb.append("- [").append(it.relationship).append("] ").append(it.url).append('\n') }
            }
            if (rec.crossRefs.isNotEmpty()) {
                sb.append('\n').append("### Cross-plugin\n")
                rec.crossRefs.forEach { ref ->
                    sb.append("- ").append(ref.pluginId).append(" - ")
                        .append(ref.refType).append(":").append(ref.refId)
                    if (ref.label.isNotEmpty()) sb.append(" - ").append(ref.label)
                    sb.append('\n')
                }
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun currentBrowserUrl(): String? {
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

    private fun encodeRecord(rec: PageRecord): JsonObject = buildJsonObject {
        put("urlHash", rec.urlHash)
        put("url", rec.url)
        if (rec.title != null) put("title", rec.title) else put("title", JsonNull)
        put("firstSeenAt", rec.firstSeenAt)
        put("lastSeenAt", rec.lastSeenAt)
        put("visitCount", rec.visitCount)
        put("userNotes", rec.userNotes)
        put("agentNotes", rec.agentNotes)
        put("tags", JsonArray(rec.tags.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        put(
            "relatedUrls",
            JsonArray(rec.relatedUrls.map { rel ->
                buildJsonObject {
                    put("url", rel.url)
                    put("relationship", rel.relationship)
                }
            }),
        )
        put(
            "crossRefs",
            JsonArray(rec.crossRefs.map { ref ->
                buildJsonObject {
                    put("pluginId", ref.pluginId)
                    put("refId", ref.refId)
                    put("refType", ref.refType)
                    put("label", ref.label)
                }
            }),
        )
    }

    private fun formatTime(epochMs: Long): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.ROOT)
        return sdf.format(java.util.Date(epochMs))
    }

    private companion object {
        const val URL_SCHEMA = """
            {"type":"object","properties":{
              "url":{"type":"string","description":"Page URL (required)."}
            },"required":["url"]}
        """

        const val SET_NOTES_SCHEMA = """
            {"type":"object","properties":{
              "url":{"type":"string","description":"Page URL (required)."},
              "notes":{"type":"string","description":"Replacement notes text."}
            },"required":["url","notes"]}
        """

        const val TAG_SCHEMA = """
            {"type":"object","properties":{
              "url":{"type":"string","description":"Page URL (required)."},
              "tag":{"type":"string","description":"Tag to add (required)."}
            },"required":["url","tag"]}
        """

        const val RECORD_VISIT_SCHEMA = """
            {"type":"object","properties":{
              "url":{"type":"string","description":"Page URL (required)."},
              "title":{"type":"string","description":"Optional title to set on the record."}
            },"required":["url"]}
        """

        const val ATTACH_SCHEMA = """
            {"type":"object","properties":{
              "url":{"type":"string","description":"Page URL (required)."},
              "pluginId":{"type":"string","description":"Attaching plugin id (required)."},
              "refId":{"type":"string","description":"Reference id (required)."},
              "refType":{"type":"string","description":"Reference type (required)."},
              "label":{"type":"string","description":"Optional human-readable label."}
            },"required":["url","pluginId","refId","refType"]}
        """

        const val RELATE_SCHEMA = """
            {"type":"object","properties":{
              "urlA":{"type":"string","description":"Source page URL (required)."},
              "urlB":{"type":"string","description":"Target page URL (required)."},
              "relationship":{"type":"string","description":"One of see-also, caused-by, blocks, supersedes (required)."}
            },"required":["urlA","urlB","relationship"]}
        """

        const val SEARCH_SCHEMA = """
            {"type":"object","properties":{
              "query":{"type":"string","description":"Substring to find across url/title/notes/tags (required)."}
            },"required":["query"]}
        """

        const val EXPORT_SCHEMA = """
            {"type":"object","properties":{
              "format":{"type":"string","description":"json or markdown (default json)."}
            }}
        """
    }
}
