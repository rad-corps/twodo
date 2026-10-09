package app.twodo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.twodo.model.SpaceKind
import app.twodo.model.TodoList

/** Each kind of shared space has an icon and a name; new kinds add theirs here. */
val SpaceKind.icon: ImageVector
    get() = when (this) {
        SpaceKind.LIST -> Icons.Outlined.Checklist
        SpaceKind.DIARY -> Icons.Outlined.CalendarMonth
    }

val SpaceKind.label: String
    get() = when (this) {
        SpaceKind.LIST -> "List"
        SpaceKind.DIARY -> "Diary"
    }

/** The list's own theme, or [appTheme] if it follows the app. */
fun TodoList.theme(appTheme: AppTheme): AppTheme = themeId?.let(::themeById) ?: appTheme

/**
 * The list's kind icon on a small tile in the list's theme colours, so lists and diaries are easy to
 * tell apart at a glance.
 */
@Composable
internal fun SpaceBadge(list: TodoList, appTheme: AppTheme, modifier: Modifier = Modifier) {
    val colors = list.theme(appTheme).colors
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier.size(40.dp).clip(shape).background(colors.primaryContainer).border(1.dp, colors.outlineVariant, shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(list.kind.icon, list.kind.label, tint = colors.primary, modifier = Modifier.size(22.dp))
    }
}
