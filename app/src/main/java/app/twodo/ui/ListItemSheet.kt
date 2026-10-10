@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package app.twodo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.twodo.model.AuditEntry
import app.twodo.model.Item

/**
 * Change a list item: its words, which section it's in, or delete it. For a heading: rename or delete
 * (deleting a heading leaves its items where they are).
 */
@Composable
internal fun ListItemSheet(
    item: Item,
    /** The list's headings, for moving the item into a section. */
    headings: List<Item>,
    /** The heading the item is under now, if any. */
    section: Item?,
    onDismiss: () -> Unit,
    onSave: (text: String, headingId: String?) -> Unit,
    onDelete: () -> Unit,
    history: List<AuditEntry> = emptyList(),
    historyRow: @Composable (AuditEntry) -> Unit = {},
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var text by remember { mutableStateOf(item.text) }
    var headingId by remember { mutableStateOf(section?.id) }
    var showHistory by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            Text(if (item.heading) "Edit heading" else "Edit item", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(if (item.heading) "Heading" else "Item") },
                textStyle = MaterialTheme.typography.titleMedium,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (!item.heading && headings.isNotEmpty()) {
                Text(
                    "Section",
                    Modifier.padding(top = 20.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = headingId == null, onClick = { headingId = null }, label = { Text("None") })
                    headings.forEach { h ->
                        FilterChip(selected = headingId == h.id, onClick = { headingId = h.id }, label = { Text(h.text) })
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { if (text.isNotBlank()) onSave(text, headingId) },
                enabled = text.isNotBlank(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) { Text("Save", style = MaterialTheme.typography.titleMedium) }
            TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                Text(if (item.heading) "Delete heading (keeps its items)" else "Delete", color = MaterialTheme.colorScheme.error)
            }
            if (history.isNotEmpty()) {
                TextButton(onClick = { showHistory = !showHistory }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (showHistory) "Hide history" else "History (${history.size})")
                }
                if (showHistory) history.forEach { historyRow(it) }
            }
        }
    }
}
