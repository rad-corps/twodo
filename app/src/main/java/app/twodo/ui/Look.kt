package app.twodo.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.twodo.TwoDoApp
import app.twodo.model.Look
import app.twodo.model.SpaceKind
import app.twodo.model.TodoList
import app.twodo.model.sharedLook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import android.graphics.Color as AndroidColor

/** Theme id meaning "take the colours from the group's photo". */
const val PHOTO_THEME = "photo"

/** Everything that decides how a screen looks: its colours, and a photo behind it (with how strongly it shows). */
class ResolvedLook(val theme: AppTheme, val photo: ImageBitmap?, val strength: Float)

/** Accent colours offered alongside the theme's own and the photo's. */
val ACCENTS: List<Long> = listOf(
    0xFF2E6B5E, 0xFF8FC9B4, 0xFF268BD2, 0xFF5E81AC, 0xFF8839EF, 0xFFCBA6F7,
    0xFFD7827E, 0xFFEB6F92, 0xFFDC322F, 0xFFAF3A03, 0xFFFABD2F, 0xFF3F8F3F,
)

/** The group a space takes its look from: itself if it's a group, its group if it's in one. */
fun TodoList.lookGroup(lists: Map<String, TodoList>): TodoList? =
    if (kind == SpaceKind.GROUP) this else groupId?.let { lists[it] }

/**
 * How [list] looks on this phone. Spaces in a group share the group's look (set by anyone in it);
 * other lists have this phone's own theme for them, or the app's.
 */
@Composable
fun rememberLook(app: TwoDoApp, list: TodoList?, appTheme: AppTheme): ResolvedLook {
    val lists by app.repo.lists.collectAsStateWithLifecycle()
    // Photos are written to disk after the group changes; this ticks when one lands.
    val photosReady by app.repo.photos.collectAsStateWithLifecycle()
    val group = list?.lookGroup(lists)
    if (group == null) return ResolvedLook(list?.theme(appTheme) ?: appTheme, null, 0f)
    val look = group.sharedLook
    val photo by produceState<ImageBitmap?>(null, look.photoId, photosReady) {
        value = look.photoId?.let { app.repo.photoFile(it) }?.takeIf { it.exists() }?.let { loadPhoto(it) }
    }
    val photoTheme = remember(photo) { photo?.let { photoTheme(it) } }
    return remember(look, photo, photoTheme, appTheme) { resolve(look, appTheme, photo, photoTheme) }
}

fun resolve(look: Look, appTheme: AppTheme, photo: ImageBitmap?, photoTheme: AppTheme?): ResolvedLook {
    val base = when (look.themeId) {
        null -> appTheme
        PHOTO_THEME -> photoTheme ?: appTheme
        else -> themeById(look.themeId)
    }
    val theme = look.accent?.let { base.withAccent(Color(it)) } ?: base
    return ResolvedLook(theme, photo, look.photoStrength)
}

/**
 * The screen's theme, with its photo (if any) filling the background under a veil of the theme's
 * background colour, so text stays readable. Surfaces go see-through so the photo shows behind lists.
 */
@Composable
fun LookSurface(look: ResolvedLook, content: @Composable () -> Unit) {
    val photo = look.photo
    val colors = look.theme.colors
    Box(Modifier.fillMaxSize().background(colors.background)) {
        if (photo != null) {
            Image(photo, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(colors.background.copy(alpha = veil(look.strength))))
        }
        val scheme = if (photo == null) colors else colors.copy(background = Color.Transparent, surface = Color.Transparent)
        // Screens without a Scaffold (e.g. the welcome screen) still get the theme's text colour.
        MaterialTheme(colorScheme = scheme) {
            CompositionLocalProvider(LocalContentColor provides colors.onBackground, content = content)
        }
    }
}

/** How opaque the veil over the photo is: a strong photo still keeps a quarter veil for legibility. */
fun veil(strength: Float) = 1f - strength.coerceIn(0f, 1f) * 0.75f

suspend fun loadPhoto(file: File): ImageBitmap? = withContext(Dispatchers.IO) {
    runCatching { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }.getOrNull()
}

/**
 * Reads a picked photo and shrinks it to a backdrop-sized JPEG (longest side [MAX_SIDE] px), small
 * enough to send to everyone in the group in a few pieces.
 */
suspend fun preparePhoto(context: Context, uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
    runCatching {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val scale = MAX_SIDE.toFloat() / maxOf(info.size.width, info.size.height)
            if (scale < 1f) decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            out.toByteArray()
        }
    }.getOrNull()
}

private const val MAX_SIDE = 960
private const val JPEG_QUALITY = 60

/**
 * A theme taken from a photo: light or dark to match it, its most vivid colour as the accent, and
 * quiet backgrounds tinted with that colour.
 */
fun photoTheme(photo: ImageBitmap): AppTheme {
    val bitmap = Bitmap.createScaledBitmap(photo.asAndroidBitmap(), 48, 48, true)
    val hsv = FloatArray(3)
    val weights = DoubleArray(HUE_BUCKETS)
    val hueSums = DoubleArray(HUE_BUCKETS)
    val satSums = DoubleArray(HUE_BUCKETS)
    var lightness = 0.0
    for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
        val pixel = bitmap.getPixel(x, y)
        lightness += (0.2126 * AndroidColor.red(pixel) + 0.7152 * AndroidColor.green(pixel) + 0.0722 * AndroidColor.blue(pixel)) / 255
        AndroidColor.colorToHSV(pixel, hsv)
        if (hsv[1] < 0.2f || hsv[2] < 0.25f) continue
        val bucket = (hsv[0] / 360f * HUE_BUCKETS).toInt().coerceIn(0, HUE_BUCKETS - 1)
        val weight = (hsv[1] * hsv[2]).toDouble()
        weights[bucket] += weight
        hueSums[bucket] += hsv[0] * weight
        satSums[bucket] += hsv[1] * weight
    }
    val dark = lightness / (bitmap.width * bitmap.height) < 0.55
    val best = weights.indices.maxBy { weights[it] }
    val vivid = weights[best] > 0.0
    val hue = if (vivid) (hueSums[best] / weights[best]).toFloat() else 160f
    val sat = if (vivid) (satSums[best] / weights[best]).toFloat().coerceIn(0.45f, 0.8f) else 0.35f
    fun hsv(s: Float, v: Float) = Color(AndroidColor.HSVToColor(floatArrayOf(hue, s, v)))
    return if (dark) {
        theme(
            PHOTO_THEME, "From photo", true,
            background = hsv(0.25f, 0.10f), raised = hsv(0.22f, 0.16f), text = hsv(0.05f, 0.94f), muted = hsv(0.10f, 0.72f),
            accent = hsv(sat, 0.88f), error = Color(0xFFF2B8B5),
        )
    } else {
        theme(
            PHOTO_THEME, "From photo", false,
            background = hsv(0.06f, 0.98f), raised = hsv(0.08f, 0.93f), text = hsv(0.20f, 0.12f), muted = hsv(0.14f, 0.38f),
            accent = hsv(sat, 0.52f), error = Color(0xFFBA1A1A),
        )
    }
}

private const val HUE_BUCKETS = 24

/** A few distinct colours from the photo, offered as accents. */
fun photoAccents(photo: ImageBitmap): List<Long> {
    val bitmap = Bitmap.createScaledBitmap(photo.asAndroidBitmap(), 32, 32, true)
    val hsv = FloatArray(3)
    val buckets = mutableMapOf<Int, Pair<Double, FloatArray>>()
    for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
        AndroidColor.colorToHSV(bitmap.getPixel(x, y), hsv)
        if (hsv[1] < 0.3f || hsv[2] < 0.3f) continue
        val bucket = (hsv[0] / 30f).toInt()
        val (weight, sample) = buckets[bucket] ?: (0.0 to hsv.copyOf())
        buckets[bucket] = (weight + hsv[1] * hsv[2]) to sample
    }
    return buckets.values.sortedByDescending { it.first }.take(3).map { (_, sample) ->
        AndroidColor.HSVToColor(floatArrayOf(sample[0], sample[1].coerceIn(0.45f, 0.85f), 0.75f)).toLong() and 0xFFFFFFFFL
    }
}
