# Page Memory

A persistent, URL-keyed memory store for the pages the user reads in BOSS.

Bookmarks capture URLs. Citations capture quotes. This plugin captures the rest:
the user's own notes, the agent's notes, the tags, the relationship between two
pages the user just bounced between, and the cross-references another plugin
left on the same URL. Per URL, per visit, across every page the user has ever
opened in BOSS.

## What it stores

For every URL the user has visited (and that any plugin has asked to record):

- `urlHash` - SHA-256 of the canonical URL (the primary key)
- `url` - the canonical URL (lower-case host, no tracking params, no fragment)
- `title` - last known title
- `firstSeenAt`, `lastSeenAt` - timestamps
- `visitCount` - integer
- `userNotes` - free-form text the user typed in the panel
- `agentNotes` - markdown notes an agent (or another plugin) attached via MCP
- `tags` - list of strings
- `relatedUrls` - list of `{url, relationship}` where relationship is one of
  `see-also`, `caused-by`, `blocks`, `supersedes`
- `crossRefs` - list of `{pluginId, refId, refType, label}` so other plugins
  can link their own records into a page (e.g. "this page is also being
  scraped by `llmrpa` job `j_42`")

## Plugin surface

### Side panel (`left_bottom`)

- See the current page's memory record (or "no memory yet" + an "Use browser
  tab" button to pick up the URL the user is on right now)
- Edit `userNotes` and save
- Add and remove tags
- Add related URLs with one of the four relationship kinds
- See cross-references left by other plugins (read-only)
- See a History section: visits, first/last seen timestamps, agent notes

### MCP tools

| Name | Read-only | Purpose |
| --- | --- | --- |
| `page_memory_get` | yes | Return the full record for a URL, or null |
| `page_memory_get_current` | yes | Same, for the active browser tab |
| `page_memory_set_notes` | no | Replace the user notes for a URL |
| `page_memory_add_tag` | no | Append a tag (deduplicated) |
| `page_memory_record_visit` | no | Bump `visitCount` / `lastSeenAt`, set title |
| `page_memory_attach` | no | Another plugin attaches its own ref |
| `page_memory_relate` | no | Add a related-URL edge between two pages |
| `page_memory_search` | yes | Substring search across `url`, `title`, `userNotes`, `agentNotes`, `tags` |
| `page_memory_export` | yes | Dump every record as JSON or Markdown |

## Why this is unique

No other BOSS plugin keeps per-URL context that survives a session. Bookmarks
remember the URL. Page Content remembers the page's text. The agent in the
terminal remembers what it just did. None of them remember what *the user*
was doing with the page - their notes, the tags they had attached, which
related pages they had linked it to, and what other plugins had already done
with it. This plugin is the first to keep that context, and to expose it both
through a human-facing panel and through the `boss` MCP server so the agent
sitting next to the user can read it.

## Bounded growth

The user could open 10,000 pages a year and the store could explode. The store
refuses to grow past:

- `MAX_RECORDS = 50,000` - the oldest by `lastSeenAt` is evicted when the cap
  is hit
- `MAX_RECORD_BYTES = 256 KiB` per record on disk
- `MAX_VALUE_BYTES = 32 KiB` per tag, note, related-URL
- `MAX_SEARCH_RESULTS = 200` - search truncates with a flag

These are documented at the top of `PageMemoryStore.kt`.

## URL canonicalisation

`UrlCanonicalizer` normalises a URL before hashing:

- lower-cases the scheme and host
- drops the default port (80 for http, 443 for https)
- sorts the query parameters by name
- drops `utm_*`, `fbclid`, `gclid`
- drops the fragment

`https://Example.com:443/a?utm_source=x&z=2&a=1#section` and
`https://example.com/a?a=1&z=2` hash to the same `urlHash`.

## Install

1. Open BOSS
2. Settings -> Plugins -> "Page Memory" -> Install
3. Open a Fluck (browser) tab - the panel starts recording visits automatically
4. The `page_memory_*` tools appear on the `boss` MCP server

## Compatibility

- `apiVersion` `1.0.93` (BOSS plugin API)
- `minBossVersion` `9.4.2`
- `type` `mixed` (panel + MCP tools)

## License

MIT
