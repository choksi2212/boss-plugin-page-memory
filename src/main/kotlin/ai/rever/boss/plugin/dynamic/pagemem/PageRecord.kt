package ai.rever.boss.plugin.dynamic.pagemem

import kotlinx.serialization.Serializable

/**
 * One persisted page's memory record.
 *
 * The primary key is [urlHash] (SHA-256 of the canonical URL); [url] is the
 * human-readable canonical form. Every other field is optional so a partial
 * record (the user has only visited a page) survives the round trip and can
 * be enriched later by notes, tags or related URLs.
 *
 * [relatedUrls] is an ordered list - the order the user typed them - and
 * [crossRefs] similarly. Both deduplicate on insert but do not re-sort, so
 * "first added" is the visible order in the panel.
 */
@Serializable
data class PageRecord(
    val urlHash: String,
    val url: String,
    val title: String? = null,
    val firstSeenAt: Long = 0L,
    val lastSeenAt: Long = 0L,
    val visitCount: Int = 0,
    val userNotes: String = "",
    val agentNotes: String = "",
    val tags: List<String> = emptyList(),
    val relatedUrls: List<RelatedUrl> = emptyList(),
    val crossRefs: List<CrossRef> = emptyList(),
)

@Serializable
data class RelatedUrl(
    val url: String,
    val relationship: String,
)

@Serializable
data class CrossRef(
    val pluginId: String,
    val refId: String,
    val refType: String,
    val label: String = "",
)

/**
 * All [relationship] values accepted by `page_memory_relate` and stored on
 * [RelatedUrl]. Kept as a small enum so the panel can render a fixed set
 * of options and MCP callers can validate against the same list.
 */
object Relationship {
    const val SEE_ALSO = "see-also"
    const val CAUSED_BY = "caused-by"
    const val BLOCKS = "blocks"
    const val SUPERSEDES = "supersedes"

    val ALL = listOf(SEE_ALSO, CAUSED_BY, BLOCKS, SUPERSEDES)
}
