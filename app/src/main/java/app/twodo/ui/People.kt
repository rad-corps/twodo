package app.twodo.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Colours people can pick for themselves: mid-tones that read on both light and dark themes (and on
 * photos), and are easy to tell apart.
 */
val PERSON_COLORS: List<Long> = listOf(
    0xFFE5484D, // red
    0xFFF76B15, // orange
    0xFFD69E00, // gold
    0xFF46A758, // green
    0xFF12A594, // teal
    0xFF0090FF, // blue
    0xFF3E63DD, // indigo
    0xFF8E4EC6, // purple
    0xFFD6409F, // pink
    0xFF978365, // bronze
)

/**
 * Who's who, colour-wise: this phone's person ([me], with [myColor] if chosen) and the colours others
 * have chosen. Anyone who hasn't chosen gets one derived from their device id, the same on every phone.
 */
class People(private val me: String, private val myColor: Long?, private val chosen: Map<String, Long>) {
    fun color(deviceId: String): Color {
        val argb = (if (deviceId == me) myColor else chosen[deviceId]) ?: defaultColor(deviceId)
        return Color(argb)
    }
}

fun defaultColor(deviceId: String): Long = PERSON_COLORS[Math.floorMod(deviceId.hashCode(), PERSON_COLORS.size)]

val LocalPeople = staticCompositionLocalOf { People("", null, emptyMap()) }

/** The colour of whoever [deviceId] is, as everyone sees it. */
@Composable
fun personColor(deviceId: String): Color = LocalPeople.current.color(deviceId)
