package app.twodo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Grid of theme swatches. With [appTheme] given, the first option is "App theme" (picks null): for a
 * list or diary that should simply follow the app.
 */
@Composable
internal fun ThemePickerDialog(
    title: String,
    selected: String?,
    appTheme: AppTheme? = null,
    icon: ImageVector? = null,
    onDismiss: () -> Unit,
    onPick: (String?) -> Unit,
) {
    val options: List<Pair<String?, AppTheme>> = listOfNotNull(appTheme?.let { null to it }) + THEMES.map { it.id to it }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.heightIn(max = 440.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(options, key = { it.first ?: "app" }) { (id, theme) ->
                    ThemeSwatch(
                        theme = theme,
                        label = if (id == null) "App theme" else theme.name,
                        selected = id == selected,
                        icon = icon,
                        onClick = { onPick(id) },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/** A miniature of the theme: its background, a fake row in its text colour, and its accent. */
@Composable
private fun ThemeSwatch(theme: AppTheme, label: String, selected: Boolean, icon: ImageVector?, onClick: () -> Unit) {
    val colors = theme.colors
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.clip(shape).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.fillMaxWidth().height(64.dp).clip(shape).background(colors.background)
                .border(
                    if (selected) 2.dp else 1.dp,
                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    shape,
                )
                .padding(10.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (icon != null) {
                        Icon(icon, null, tint = colors.primary, modifier = Modifier.size(14.dp))
                    } else {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(colors.primary))
                    }
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.height(5.dp).width(34.dp).clip(CircleShape).background(colors.onSurface))
                }
                Box(Modifier.height(5.dp).width(46.dp).clip(CircleShape).background(colors.onSurfaceVariant))
                Box(Modifier.height(10.dp).width(26.dp).clip(CircleShape).background(colors.primaryContainer))
            }
            if (selected) {
                Icon(
                    Icons.Default.Check, "Selected",
                    tint = colors.primary,
                    modifier = Modifier.align(Alignment.TopEnd).size(16.dp),
                )
            }
        }
        Text(
            label,
            Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
