package ai.rever.boss.plugin.dynamic.pagemem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.TextFieldDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Compose UI for the Page Memory panel.
 *
 * Two-pane vertical layout:
 *  - top: the current page's record (URL, title, notes editor, tags,
 *    related URLs, cross-refs from other plugins, history)
 *  - bottom: a search bar + recent records list
 *
 * The current URL is whatever the panel was last pointed at; the watcher
 * (see [ActiveTabWatcher]) updates it from the browser event bus. The
 * user can also pin a URL by typing one and pressing the "Use browser"
 * button - useful when no browser tab is active.
 */
@Composable
fun PageMemoryContent(viewModel: PageMemoryViewModel) {
    val currentUrl by viewModel.currentUrl.collectAsState()
    val currentRecord by viewModel.currentRecord.collectAsState()
    val recentRecords by viewModel.recentRecords.collectAsState()
    val userNotesDraft by viewModel.userNotesDraft.collectAsState()
    val newTagDraft by viewModel.newTagDraft.collectAsState()
    val newRelatedUrlDraft by viewModel.newRelatedUrlDraft.collectAsState()
    val newRelationshipDraft by viewModel.newRelationshipDraft.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val statusMessage by viewModel.status.collectAsState()
    val errorMessage by viewModel.error.collectAsState()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colors.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Toolbar(
                url = currentUrl,
                onUseBrowser = {
                    val url = viewModel.currentBrowserUrl()
                    if (url != null) {
                        viewModel.setCurrentUrl(url)
                    } else {
                        viewModel.refreshRecent()
                    }
                },
                onClear = { viewModel.clearCurrent() },
                onRefresh = {
                    viewModel.refreshCurrent()
                    viewModel.refreshRecent()
                },
            )
            StatusLine(statusMessage = statusMessage, errorMessage = errorMessage, onDismiss = { viewModel.clearMessages() })
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(8.dp),
            ) {
                if (currentUrl == null) {
                    NoCurrentPage(viewModel = viewModel, recentRecords = recentRecords, searchQuery = searchQuery, searchResults = searchResults)
                } else {
                    CurrentPageSection(
                        viewModel = viewModel,
                        url = currentUrl!!,
                        record = currentRecord,
                        userNotesDraft = userNotesDraft,
                        newTagDraft = newTagDraft,
                        newRelatedUrlDraft = newRelatedUrlDraft,
                        newRelationshipDraft = newRelationshipDraft,
                    )
                }
            }
        }
    }
}

@Composable
private fun Toolbar(
    url: String?,
    onUseBrowser: () -> Unit,
    onClear: () -> Unit,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colors.surface)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Page Memory",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colors.onSurface,
        )
        Spacer(modifier = Modifier.weight(1f))
        IconButton(
            onClick = onUseBrowser,
            modifier = Modifier.size(24.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.LibraryBooks,
                contentDescription = "Use current browser tab",
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
            )
        }
        if (url != null) {
            IconButton(
                onClick = onClear,
                modifier = Modifier.size(24.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Clear",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                )
            }
        }
        IconButton(
            onClick = onRefresh,
            modifier = Modifier.size(24.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "Refresh",
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun StatusLine(
    statusMessage: String?,
    errorMessage: String?,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(statusMessage, errorMessage) {
        if (statusMessage != null || errorMessage != null) {
            delay(3000)
            onDismiss()
        }
    }
    val message = errorMessage ?: statusMessage ?: return
    val isError = errorMessage != null
    val bg = if (isError) MaterialTheme.colors.error.copy(alpha = 0.15f) else MaterialTheme.colors.primary.copy(alpha = 0.15f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            fontSize = 11.sp,
            color = MaterialTheme.colors.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = onDismiss,
            modifier = Modifier.size(18.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Dismiss",
                modifier = Modifier.size(12.dp),
                tint = MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun NoCurrentPage(
    viewModel: PageMemoryViewModel,
    recentRecords: List<PageRecord>,
    searchQuery: String,
    searchResults: List<PageRecord>,
) {
    Column {
        SectionHeader(title = "No page selected")
        Text(
            text = "Click the book icon above to pick up the current browser tab, or paste a URL via the page_memory_* MCP tools.",
            fontSize = 11.sp,
            color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
            modifier = Modifier.padding(8.dp),
        )
        SectionHeader(title = "Search")
        OutlinedTextField(
            value = searchQuery,
            onValueChange = viewModel::updateSearchQuery,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            placeholder = { Text("Search notes, titles, urls, tags", fontSize = 12.sp) },
            singleLine = true,
        )
        if (searchQuery.isNotBlank()) {
            if (searchResults.isEmpty()) {
                Text(
                    text = "No matches",
                    fontSize = 11.sp,
                    color = MaterialTheme.colors.onBackground.copy(alpha = 0.5f),
                    modifier = Modifier.padding(8.dp),
                )
            } else {
                for (rec in searchResults) {
                    RecordRow(rec, onClick = { viewModel.setCurrentUrl(rec.url) })
                }
            }
        }
        SectionHeader(title = "Recent")
        if (recentRecords.isEmpty()) {
            Text(
                text = "No records yet",
                fontSize = 11.sp,
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.5f),
                modifier = Modifier.padding(8.dp),
            )
        } else {
            for (rec in recentRecords) {
                RecordRow(rec, onClick = { viewModel.setCurrentUrl(rec.url) })
            }
        }
    }
}

@Composable
private fun CurrentPageSection(
    viewModel: PageMemoryViewModel,
    url: String,
    record: PageRecord?,
    userNotesDraft: String,
    newTagDraft: String,
    newRelatedUrlDraft: String,
    newRelationshipDraft: String,
) {
    Column {
        SectionHeader(title = "Current page")
        UrlLine(url)
        Text(
            text = record?.title ?: "(no title recorded yet)",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colors.onBackground,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        MetaLine(record)

        SectionHeader(title = "Notes")
        OutlinedTextField(
            value = userNotesDraft,
            onValueChange = viewModel::updateUserNotesDraft,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            placeholder = { Text("Your notes for this page...", fontSize = 12.sp) },
            minLines = 3,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = { viewModel.saveUserNotes() }) {
                Text("Save notes", fontSize = 11.sp)
            }
        }

        SectionHeader(title = "Tags")
        if (record != null && record.tags.isNotEmpty()) {
            TagsRow(record.tags, onRemove = viewModel::removeTag)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = newTagDraft,
                onValueChange = viewModel::updateNewTagDraft,
                modifier = Modifier.weight(1f),
                placeholder = { Text("New tag", fontSize = 12.sp) },
                singleLine = true,
            )
            Spacer(modifier = Modifier.width(4.dp))
            IconButton(
                onClick = { viewModel.addTag() },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add tag",
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        SectionHeader(title = "Related URLs")
        if (record != null && record.relatedUrls.isNotEmpty()) {
            Column(modifier = Modifier.padding(horizontal = 8.dp)) {
                record.relatedUrls.forEachIndexed { index, rel ->
                    RelatedUrlRow(rel, onRemove = { viewModel.removeRelatedUrl(index) })
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = newRelatedUrlDraft,
                onValueChange = viewModel::updateNewRelatedUrlDraft,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Related URL", fontSize = 12.sp) },
                singleLine = true,
            )
            Spacer(modifier = Modifier.width(4.dp))
            RelationshipPicker(selected = newRelationshipDraft, onChange = viewModel::updateNewRelationshipDraft)
            Spacer(modifier = Modifier.width(4.dp))
            IconButton(
                onClick = { viewModel.addRelatedUrl() },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add related URL",
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        if (record != null && record.crossRefs.isNotEmpty()) {
            SectionHeader(title = "Cross-plugin references")
            Column(modifier = Modifier.padding(horizontal = 8.dp)) {
                record.crossRefs.forEach { ref ->
                    CrossRefRow(ref)
                }
            }
        }

        SectionHeader(title = "History")
        if (record != null) {
            HistoryList(record)
        } else {
            Text(
                text = "Visit this URL through any plugin or tool to start recording.",
                fontSize = 11.sp,
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.5f),
                modifier = Modifier.padding(8.dp),
            )
        }
    }
}

@Composable
private fun UrlLine(url: String) {
    Text(
        text = url,
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun MetaLine(record: PageRecord?) {
    if (record == null) {
        return
    }
    val visits = record.visitCount
    val first = if (record.firstSeenAt > 0L) formatTime(record.firstSeenAt) else "-"
    val last = if (record.lastSeenAt > 0L) formatTime(record.lastSeenAt) else "-"
    Text(
        text = "Visits: $visits - first: $first - last: $last",
        fontSize = 10.sp,
        color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun TagsRow(tags: List<String>, onRemove: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        tags.forEach { tag ->
            TagChip(label = tag, onRemove = { onRemove(tag) })
            Spacer(modifier = Modifier.width(4.dp))
        }
    }
}

@Composable
private fun TagChip(label: String, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .background(MaterialTheme.colors.primary.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, fontSize = 11.sp, color = MaterialTheme.colors.primary)
        Spacer(modifier = Modifier.width(2.dp))
        IconButton(
            onClick = onRemove,
            modifier = Modifier.size(14.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Remove",
                modifier = Modifier.size(10.dp),
                tint = MaterialTheme.colors.primary,
            )
        }
    }
}

@Composable
private fun RelatedUrlRow(rel: RelatedUrl, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = rel.relationship,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colors.primary,
            modifier = Modifier.width(80.dp),
        )
        Text(
            text = rel.url,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colors.onBackground.copy(alpha = 0.7f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = onRemove,
            modifier = Modifier.size(20.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "Remove",
                modifier = Modifier.size(12.dp),
                tint = MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun CrossRefRow(ref: CrossRef) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = ref.pluginId,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colors.primary,
            modifier = Modifier.width(120.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "${ref.refType}:${ref.refId}",
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colors.onBackground.copy(alpha = 0.7f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
    if (ref.label.isNotEmpty()) {
        Text(
            text = ref.label,
            fontSize = 10.sp,
            color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 120.dp, end = 8.dp),
        )
    }
}

@Composable
private fun HistoryList(record: PageRecord) {
    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
        HistoryRow(label = "First seen", value = if (record.firstSeenAt > 0L) formatTime(record.firstSeenAt) else "-")
        HistoryRow(label = "Last seen", value = if (record.lastSeenAt > 0L) formatTime(record.lastSeenAt) else "-")
        HistoryRow(label = "Visits", value = record.visitCount.toString())
        if (record.agentNotes.isNotBlank()) {
            SectionHeader(title = "Agent notes")
            Text(
                text = record.agentNotes,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.8f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun HistoryRow(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 1.dp)) {
        Text(
            text = label,
            fontSize = 10.sp,
            color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
            modifier = Modifier.width(100.dp),
        )
        Text(
            text = value,
            fontSize = 10.sp,
            color = MaterialTheme.colors.onBackground,
        )
    }
}

@Composable
private fun RecordRow(rec: PageRecord, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colors.surface.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = rec.title ?: rec.url,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colors.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = rec.url,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onClick) {
            Text("Open", fontSize = 10.sp)
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun RelationshipPicker(selected: String, onChange: (String) -> Unit) {
    Box {
        var expanded by remember { mutableStateOf(false) }
        TextButton(onClick = { expanded = true }) {
            Text(text = selected, fontSize = 10.sp)
        }
        androidx.compose.material.DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            Relationship.ALL.forEach { rel ->
                androidx.compose.material.DropdownMenuItem(onClick = {
                    onChange(rel)
                    expanded = false
                }) {
                    Text(text = rel, fontSize = 11.sp)
                }
            }
        }
    }
}

private fun formatTime(epochMs: Long): String {
    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.ROOT)
    return sdf.format(java.util.Date(epochMs))
}
