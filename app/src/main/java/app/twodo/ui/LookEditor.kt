package app.twodo.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.twodo.TwoDoApp
import app.twodo.model.Look
import app.twodo.model.TodoList
import app.twodo.model.sharedLook
import android.graphics.BitmapFactory
import kotlinx.coroutines.launch

/**
 * Edits the group's look — background photo, colours and accent — with a live preview. Saving changes
 * it for everyone in the group.
 */
@Composable
internal fun LookEditorDialog(app: TwoDoApp, group: TodoList, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val appThemeId by app.appTheme.collectAsStateWithLifecycle()
    val appTheme = themeById(appThemeId)
    val saved = group.sharedLook
    val current = rememberLook(app, group, appTheme)

    var themeId by remember { mutableStateOf(saved.themeId) }
    var accent by remember { mutableStateOf(saved.accent) }
    var strength by remember { mutableFloatStateOf(saved.photoStrength) }
    // A newly picked photo (not saved yet), or the removal of the current one.
    var newPhoto by remember { mutableStateOf<ByteArray?>(null) }
    var newPhotoImage by remember { mutableStateOf<ImageBitmap?>(null) }
    var removePhoto by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }

    val photo = newPhotoImage ?: current.photo.takeIf { !removePhoto }
    val photoTheme = remember(photo) { photo?.let { photoTheme(it) } }
    val photoAccents = remember(photo) { photo?.let { photoAccents(it) }.orEmpty() }
    val candidate = Look(themeId = themeId.takeIf { it != PHOTO_THEME || photo != null }, accent = accent, photoStrength = strength)
    val preview = resolve(candidate, appTheme, photo, photoTheme)

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        loading = true
        scope.launch {
            val bytes = preparePhoto(context, uri)
            loading = false
            if (bytes != null) {
                newPhoto = bytes
                newPhotoImage = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                removePhoto = false
                // A new photo usually wants its own colours.
                themeId = PHOTO_THEME
                accent = null
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Look of ${group.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Everyone in ${group.name} sees the same look.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                LookPreview(preview, group.name)
                Spacer(Modifier.height(16.dp))

                Label("Background photo")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                        Icon(Icons.Outlined.Image, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (photo == null) "Choose a photo" else "Change photo")
                    }
                    if (loading) {
                        Spacer(Modifier.width(12.dp))
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                    if (photo != null) {
                        TextButton(onClick = {
                            removePhoto = true
                            newPhoto = null
                            newPhotoImage = null
                            if (themeId == PHOTO_THEME) themeId = null
                        }) { Text("Remove") }
                    }
                }
                if (photo != null) {
                    Text("How much the photo shows", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Slider(value = strength, onValueChange = { strength = it }, valueRange = 0.1f..1f)
                }
                Spacer(Modifier.height(8.dp))

                Label("Colours")
                val options = buildList<Pair<String?, AppTheme>> {
                    add(null to appTheme)
                    if (photoTheme != null) add(PHOTO_THEME to photoTheme)
                    THEMES.forEach { add(it.id to it) }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(options, key = { it.first ?: "own" }) { (id, theme) ->
                        Box(Modifier.width(88.dp)) {
                            ThemeSwatch(
                                theme = theme,
                                label = when (id) {
                                    null -> "Each phone's own"
                                    PHOTO_THEME -> "From photo"
                                    else -> theme.name
                                },
                                selected = id == candidate.themeId,
                                icon = null,
                                onClick = { themeId = id },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))

                Label("Highlight colour")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    // "Auto": the colours' own accent.
                    val auto = resolve(candidate.copy(accent = null), appTheme, photo, photoTheme).theme.colors.primary
                    item { AccentDot(null, auto, accent == null) { accent = null } }
                    items((photoAccents + ACCENTS).distinct()) { argb -> AccentDot(argb, Color(argb), accent == argb) { accent = argb } }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val photoBytes = newPhoto
                val remove = removePhoto
                onDismiss()
                app.save { app.repo.setGroupLook(group.id, candidate, photoBytes, remove) }
            }, enabled = !loading) { Text("Save for everyone") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Label(text: String) {
    Text(text, Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

/** A small mock-up of the group's home in the candidate look. */
@Composable
private fun LookPreview(look: ResolvedLook, title: String) {
    val colors = look.theme.colors
    val shape = RoundedCornerShape(16.dp)
    Box(Modifier.fillMaxWidth().height(150.dp).clip(shape).border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape).background(colors.background)) {
        look.photo?.let {
            Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(colors.background.copy(alpha = veil(look.strength))))
        }
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = colors.onBackground)
            Spacer(Modifier.height(10.dp))
            PreviewRow("Milk", true, colors.primary, colors.outline)
            PreviewRow("Bread", false, colors.outline, colors.onBackground)
            Spacer(Modifier.height(6.dp))
            Box(Modifier.clip(RoundedCornerShape(10.dp)).background(colors.primaryContainer).padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text("Swimming 3:30 PM", style = MaterialTheme.typography.labelLarge, color = colors.onPrimaryContainer)
            }
        }
    }
}

@Composable
private fun PreviewRow(text: String, checked: Boolean, tint: Color, textColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Icon(if (checked) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank, null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = textColor)
    }
}

@Composable
private fun AccentDot(argb: Long?, color: Color, selected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(color)
                .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(Icons.Default.Check, "Selected", tint = Color.White, modifier = Modifier.size(18.dp))
        }
        Text(
            if (argb == null) "Auto" else "",
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
