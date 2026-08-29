package app.rcq.android.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Frosted-glass slices of the wallpaper under chrome (founder items Л2.9 and
 * Л2.12: blurred chat header / input bar, blurred section headers).
 *
 * The house policy at [LocalHomeVeil] still stands: no backdrop blur, ever —
 * `Modifier.blur` blurs a composable's own content (and is a no-op below API
 * 31), and re-sampling what is already drawn with a RenderEffect every frame
 * is a price the target phones cannot pay. What CAN be paid, once, is blurring
 * the wallpaper ITSELF: it is a static image that never scrolls, so a single
 * tiny pre-blurred copy (the [blurForSpoiler] trick: scale down, let the
 * bilinear upscale at draw time be the blur) serves every piece of chrome on
 * the screen. Each slice just draws that copy shifted by minus its own
 * position, so it lines up with the sharp wallpaper behind it and stays put
 * while the element it backs scrolls or the keyboard pushes it around.
 *
 * ⚠ The element's position is written in [onGloballyPositioned] (fires on
 * every scroll frame for a moving row) into a [MutableState] that is read ONLY
 * inside the draw phase — position changes invalidate the draw, never the
 * composition. Same discipline as the press-scale rows in HomeScreen.
 *
 * Presets need no bitmap at all: a Gaussian blur of a vertical gradient is the
 * same gradient, so a slice of it is the same [Brush] with its stops offset to
 * where the element stands. Only a custom photo pays for a real (tiny) bitmap.
 *
 * With no wallpaper the ground is [SliceGround.None] and the modifier draws
 * nothing — screens without a wallpaper cannot regress.
 */
internal sealed interface SliceGround {
    object None : SliceGround

    /** Preset gradient: its blur is itself. */
    data class Gradient(val stops: List<Color>) : SliceGround

    /** Custom photo: a small pre-blurred copy, upscaled at draw time. */
    data class Frost(val image: ImageBitmap) : SliceGround
}

/** Shared state between the full-screen wallpaper layer and the chrome slices
 *  drawn over it. One instance per screen (chat and home wallpapers are
 *  separate selections, so each screen builds its own). */
internal class WallpaperSlices {
    /** Written during layout by [wallpaperSliceLayer]; only used to translate
     *  element coordinates into the layer's space. */
    var layerCoords: LayoutCoordinates? = null

    /** The layer's size, read in the draw phase for the crop mapping. */
    val layerSize: MutableState<IntSize> = mutableStateOf(IntSize.Zero)

    val ground: MutableState<SliceGround> = mutableStateOf(SliceGround.None)

    val active: Boolean get() = ground.value != SliceGround.None
}

/** Ambient slices for composables that cannot take a parameter without
 *  threading it through half the app (SectionHeader in Common.kt). Null on
 *  screens that never provide one. */
internal val LocalWallpaperSlices = compositionLocalOf<WallpaperSlices?> { null }

/**
 * Builds the slice state for one screen's wallpaper selection.
 *
 * [bg] is the stored selection ("", "preset:<id>", "custom"), [file] the
 * custom image (the same pair every wallpaper helper in ChatBackground.kt
 * takes). The frost bitmap is produced off the main thread and re-produced
 * when the file is replaced, exactly like [WallpaperBackground] itself.
 */
@Composable
internal fun rememberWallpaperSlices(bg: String, file: java.io.File): WallpaperSlices {
    val slices = remember { WallpaperSlices() }
    val stamp = if (bg == "custom") file.lastModified() else 0L
    LaunchedEffect(bg, stamp) {
        slices.ground.value = when {
            bg.startsWith("preset:") ->
                ChatBackgrounds.preset(bg.removePrefix("preset:"))
                    ?.let { SliceGround.Gradient(it.stops) } ?: SliceGround.None
            bg == "custom" ->
                withContext(Dispatchers.IO) { frostBitmap(file) }
                    ?.let { SliceGround.Frost(it) } ?: SliceGround.None
            else -> SliceGround.None
        }
    }
    return slices
}

/** Marks the full-screen wallpaper layer the slices align themselves to. Put
 *  it on the Box that draws the sharp wallpaper (and nothing else). */
internal fun Modifier.wallpaperSliceLayer(slices: WallpaperSlices): Modifier =
    onGloballyPositioned {
        slices.layerCoords = it
        if (slices.layerSize.value != it.size) slices.layerSize.value = it.size
    }

/**
 * Draws the blurred wallpaper behind this element, aligned with the sharp
 * wallpaper underneath, plus an optional [veil] fill on top for text contrast
 * (the veil is the caller's usual theme-background wash; passing it here
 * instead of a separate `.background` keeps the two in one draw).
 *
 * No-op when [slices] is null or there is no wallpaper.
 */
internal fun Modifier.wallpaperSlice(slices: WallpaperSlices?, veil: Color? = null): Modifier =
    if (slices == null) this else composed {
        val pos = remember { mutableStateOf(Offset.Zero) }
        this
            .onGloballyPositioned { coords ->
                val layer = slices.layerCoords ?: return@onGloballyPositioned
                if (!layer.isAttached) return@onGloballyPositioned
                val p = layer.localPositionOf(coords, Offset.Zero)
                if (pos.value != p) pos.value = p
            }
            .drawBehind {
                val ground = slices.ground.value
                if (ground == SliceGround.None) return@drawBehind
                clipRect {
                    val layer = slices.layerSize.value
                    val p = pos.value
                    when (ground) {
                        is SliceGround.Gradient -> {
                            if (ground.stops.size == 1) drawRect(ground.stops[0])
                            else if (layer.height > 0) drawRect(
                                Brush.verticalGradient(
                                    colors = ground.stops,
                                    startY = -p.y,
                                    endY = layer.height - p.y,
                                ),
                            )
                        }
                        is SliceGround.Frost -> {
                            val img = ground.image
                            if (layer.width > 0 && layer.height > 0) {
                                // The same ContentScale.Crop mapping WallpaperBackground
                                // uses for the sharp image, so the frost lines up with it.
                                val s = max(
                                    layer.width / img.width.toFloat(),
                                    layer.height / img.height.toFloat(),
                                )
                                val offX = (layer.width - img.width * s) / 2f
                                val offY = (layer.height - img.height * s) / 2f
                                translate(offX - p.x, offY - p.y) {
                                    drawImage(
                                        img,
                                        dstSize = IntSize(
                                            (img.width * s).roundToInt(),
                                            (img.height * s).roundToInt(),
                                        ),
                                    )
                                }
                            }
                        }
                        SliceGround.None -> {}
                    }
                    veil?.let { drawRect(it) }
                }
            }
    }

/** Longest side of the intermediate decode, and of the kept frost bitmap.
 *
 *  The blur IS the resampling: decode small, halve it again, then scale back
 *  up to [FROST_SIDE] with filtering — the double resample rounds off the
 *  blocks a single downscale keeps (same trick as blurForSpoiler, gentler
 *  ratio: 40px of source stretched over a 2400px screen reads as frosted
 *  glass, 18px read as a spoiler). Kept at 320 longest side, ARGB ≈ 0.25 MB —
 *  noise next to the 15 MB sharp wallpaper already resident. */
private const val FROST_DECODE_SIDE = 320
private const val FROST_CORE_SIDE = 40
private const val FROST_SIDE = 320

private fun frostBitmap(file: java.io.File): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    val longest = max(bounds.outWidth, bounds.outHeight)
    if (longest <= 0) return@runCatching null
    var sample = 1
    while (longest / (sample * 2) >= FROST_DECODE_SIDE) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    val src = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return@runCatching null
    fun scaledTo(b: Bitmap, side: Int): Bitmap {
        val k = side / max(b.width, b.height).toFloat()
        val w = max(1, (b.width * k).roundToInt())
        val h = max(1, (b.height * k).roundToInt())
        return Bitmap.createScaledBitmap(b, w, h, true)
    }
    val core = scaledTo(src, FROST_CORE_SIDE)
    if (core !== src) src.recycle()
    val frost = scaledTo(core, FROST_SIDE)
    if (frost !== core) core.recycle()
    frost.asImageBitmap()
}.getOrNull()
