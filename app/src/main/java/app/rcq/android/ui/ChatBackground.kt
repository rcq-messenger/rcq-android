package app.rcq.android.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rcq.android.R
import app.rcq.android.data.LocalStores
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class ChatBgPreset(
    val id: String,
    val label: String,
    val brush: Brush,
    /** The colour at the TOP edge of [brush]. Chrome that is drawn OVER the
     *  wallpaper — the home header — sits on this rather than on the theme's
     *  background, and takes its foreground from it. See [wallpaperChrome]. */
    val topColor: Color,
    /** Which theme this wallpaper was drawn for. See [ChatBackgrounds.offered]. */
    val forDark: Boolean,
)

/** Built-in chat wallpapers. Global (one for the whole app), applied behind the
 *  message list in every chat. Founder asked for presets + a custom image. */
internal object ChatBackgrounds {
    val presets = listOf(
        wallpaper("ocean", "Ocean", listOf(Color(0xFF1A2980), Color(0xFF26D0CE)), forDark = true),
        wallpaper("midnight", "Midnight", listOf(Color(0xFF0F2027), Color(0xFF203A43), Color(0xFF2C5364)), forDark = true),
        wallpaper("forest", "Forest", listOf(Color(0xFF134E5E), Color(0xFF71B280)), forDark = true),
        wallpaper("graphite", "Graphite", listOf(Color(0xFF232526), Color(0xFF414345)), forDark = true),
        wallpaper("sunset", "Sunset", listOf(Color(0xFFFF8008), Color(0xFFFFC837)), forDark = false),
        wallpaper("lavender", "Lavender", listOf(Color(0xFFE0C3FC), Color(0xFF8EC5FC)), forDark = false),
        wallpaper("rose", "Rose", listOf(Color(0xFFFFDEE9), Color(0xFFB5FFFC)), forDark = false),
        wallpaper("cream", "Cream", listOf(Color(0xFFF3EFE7)), forDark = false),
    )

    /**
     * The wallpapers to OFFER for the theme currently in use, plus whichever
     * one is already selected.
     *
     * Founder's call: a theme and a wallpaper are picked on two different
     * screens, minutes apart, and nothing stopped the pairing that fights
     * itself. The four dark gradients under a light theme, or the four pale
     * ones under a dark theme, are the pairings that produced #554 (the header
     * disappearing into its own background) and the founder's iOS report about
     * a light wallpaper eating the UIN. Chrome now recolours itself
     * ([wallpaperChrome]) so neither is unreadable any more, but a dark app
     * with a cream wallpaper is still nobody's intention. So each theme is
     * offered the set that was drawn for it.
     *
     * ⚠ [selectedId] is added back unconditionally, and this is the whole
     * reason the parameter exists. Someone who chose Midnight and later moved
     * to the light theme must still see Midnight in the grid, ticked. Filtering
     * it out would show a picker with NOTHING selected while the wallpaper it
     * refuses to name is plainly on screen behind it. Rendering never consults
     * this list at all: [preset] resolves every id in every theme, so a stored
     * choice keeps drawing exactly as before whatever the theme says.
     */
    fun offered(dark: Boolean, selectedId: String?): List<ChatBgPreset> =
        presets.filter { it.forDark == dark || it.id == selectedId }

    /** Built from the stops rather than from a ready-made Brush so the top
     *  colour cannot drift away from the gradient it is supposed to describe —
     *  a Brush does not hand its stops back. One stop = a flat fill. */
    private fun wallpaper(id: String, label: String, stops: List<Color>, forDark: Boolean) = ChatBgPreset(
        id = id,
        label = label,
        brush = if (stops.size == 1) SolidColor(stops[0]) else Brush.verticalGradient(stops),
        topColor = stops[0],
        forDark = forDark,
    )

    fun preset(id: String) = presets.firstOrNull { it.id == id }
}

/** Renders the selected chat wallpaper behind the message list. Renders nothing
 *  for the default ("") — the chat's theme background shows through. */
@Composable
internal fun ChatBackground() {
    val bg by LocalStores.chatBackground.collectAsState()
    val context = LocalContext.current
    WallpaperBackground(bg, remember { LocalStores.chatBgFile(context) })
}

/** Same wallpaper, but for the HOME / chat-list screen (separate selection). */
@Composable
internal fun HomeBackground() {
    val bg by LocalStores.homeBackground.collectAsState()
    val context = LocalContext.current
    WallpaperBackground(bg, remember { LocalStores.homeBgFile(context) })
}

/**
 * How opaque a container drawn OVER the home wallpaper should be.
 *
 * 1f, always, when there is no wallpaper: every fill is then byte-identical to
 * what it has always been, and a screen with no wallpaper cannot regress.
 *
 * With a wallpaper it is one of the two constants below, chosen by whether that
 * wallpaper's tone agrees with the theme. Founder's report (iOS, home half of
 * item 18): with a wallpaper set, the list containers stayed a flat theme
 * colour and covered the picture completely, so choosing a wallpaper for the
 * chat LIST did almost nothing visible, unlike choosing one for a chat where
 * the bubbles leave gaps. Android had it worse than iOS: every contact row,
 * group row and request row filled itself with an opaque `bgPrimary`, which is
 * the ENTIRE width of the list, so the only wallpaper anyone ever saw was the
 * few pixels behind the section headers (which already carried a 0.7 veil) and
 * the empty state. The comment on [HomeBackground]'s call site claimed
 * "transparent rows show it, headers stay opaque", which was the truth exactly
 * backwards.
 *
 * A veil rather than plain transparency, and the veil is the THEME's own
 * background colour, because it has to do two jobs at once. It lets the
 * wallpaper through, and it restores the ground the row's text was designed
 * for: dark theme text keeps a dark strip under it over a pale wallpaper, light
 * theme text a pale one over a dark wallpaper. That is why nothing in the list
 * needs [homeChrome]: the veil, not the wallpaper, is what these rows stand on.
 *
 * ⚠ NOT a blur. A real backdrop blur means sampling what is already drawn
 * underneath, which Compose only offers from API 31 (`Modifier.blur` blurs a
 * composable's own CONTENT, not what is behind it, so on a transparent row it
 * blurs the text and leaves the wallpaper sharp: the exact opposite of the
 * ask). Doing it properly means drawing the wallpaper a second time into each
 * container with a RenderEffect, per frame, on a list that scrolls, which is
 * not a trade this screen should make for the phones this app runs on. The
 * translucency is the part of "translucent/blurred" that is free.
 */
internal val LocalHomeVeil = compositionLocalOf { 1f }

/** Opacity of a container standing on a wallpaper that AGREES with the theme:
 *  a dark wallpaper under the dark theme, a pale one under the light theme.
 *
 *  Generous, because it can afford to be. Blending 28% of a dark wallpaper into
 *  the dark theme's own dark background barely moves the colour the row's text
 *  was designed against, so the text keeps the contrast it has always had and
 *  the picture is properly visible through the list. */
private const val WALLPAPER_VEIL = 0.72f

/** Opacity of a container standing on a wallpaper that FIGHTS the theme: a pale
 *  wallpaper under the dark theme, or the reverse.
 *
 *  ⚠ This second constant is the whole reason the veil is not one number. At
 *  0.72 the dark theme's secondary text (0x9A on 0x1A) drops from a contrast
 *  ratio of 6.1 to 3.0 over a WHITE wallpaper, which is under AA and is exactly
 *  the founder's report ("a light wallpaper makes the header hard to read"),
 *  just moved from the header down into the list. At 0.9 the same pairing lands
 *  on ~0x31 and the ratio comes back to 4.6, while a tenth of a bright
 *  wallpaper is still plainly visible as a tint through the row. The light
 *  theme over a black wallpaper is the mirror case and lands at 5.9.
 *
 *  The built-in gradients cannot reach this branch any more, since each theme is
 *  only offered the set drawn for it ([ChatBackgrounds.offered]). A custom photo
 *  can, and a photo is precisely where a guess would be wrong. */
private const val WALLPAPER_VEIL_CONTRARY = 0.9f

/**
 * The veil for the HOME wallpaper: how opaque a list container should be, or 1f
 * when there is no wallpaper and nothing needs veiling at all.
 *
 * ⚠ "There is a wallpaper" is the same test [WallpaperBackground] makes, not
 * merely "the setting is not empty". A stored `preset:` id from a build that had
 * a wallpaper we have since dropped resolves to nothing and paints nothing, and
 * veiling the list over a wallpaper that is not there would wash the theme out
 * for no reason at all.
 *
 * The tone question is the same one [wallpaperChrome] asks for the header, and
 * it is asked with the same function, so the two cannot drift apart: a
 * wallpaper the header calls dark is a wallpaper the list calls dark.
 */
@Composable
internal fun homeVeil(): Float {
    val bg by LocalStores.homeBackground.collectAsState()
    val context = LocalContext.current
    val top = wallpaperTopColor(bg, remember { LocalStores.homeBgFile(context) }) ?: return 1f
    // A custom image whose tone has not been measured yet reports null above and
    // therefore no veil for a frame or two, which is the honest answer: it also
    // has not drawn yet.
    return if (top.needsLightChrome() == RcqTheme.colors.isDark) WALLPAPER_VEIL
    else WALLPAPER_VEIL_CONTRARY
}

@Composable
private fun WallpaperBackground(bg: String, file: java.io.File) {
    when {
        bg.startsWith("preset:") ->
            ChatBackgrounds.preset(bg.removePrefix("preset:"))?.let {
                Box(Modifier.fillMaxSize().background(it.brush))
            }
        bg == "custom" -> {
            // Re-read when the file is replaced (lastModified changes).
            val stamp = file.lastModified()
            val img by produceState<ImageBitmap?>(initialValue = null, stamp) {
                value = withContext(Dispatchers.IO) {
                    runCatching { BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() }.getOrNull()
                }
            }
            img?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
    }
}

/**
 * Colours for chrome that is drawn straight ON TOP of the home wallpaper — the
 * header's name, account glyph, UIN and overflow dots, which have no fill of
 * their own and so sit on whatever the wallpaper puts under them.
 *
 * ⚠ [RcqTheme.colors] is the WRONG source for those. It follows light/dark
 * mode, and the wallpaper is picked independently of the mode, so the two
 * disagree the moment somebody in the light theme chooses "Midnight": the
 * header keeps its black glyphs and disappears into the wallpaper (#554,
 * "имя сверху, глиф аватарки и три точки справа чёрные"). The dark theme has
 * the mirror-image bug on "Rose" and "Cream". What the header actually stands
 * on is the TOP of the wallpaper, so that is what chooses its foregrounds.
 *
 * Falls back to the theme's own colours when no wallpaper is set — then the
 * header really is standing on `bgPrimary` and nothing needs deciding.
 */
@Composable
internal fun homeChrome(): RcqColors {
    val bg by LocalStores.homeBackground.collectAsState()
    val context = LocalContext.current
    return wallpaperChrome(bg, remember { LocalStores.homeBgFile(context) })
}

/** The same, for chrome over the CHAT wallpaper (a separate selection). */
@Composable
internal fun chatChrome(): RcqColors {
    val bg by LocalStores.chatBackground.collectAsState()
    val context = LocalContext.current
    return wallpaperChrome(bg, remember { LocalStores.chatBgFile(context) })
}

@Composable
private fun wallpaperChrome(bg: String, file: java.io.File): RcqColors {
    val top = wallpaperTopColor(bg, file)
    return if (top == null) RcqTheme.colors else rcqColorsFor(top.needsLightChrome())
}

/** What the top of the wallpaper is, or null when there is no wallpaper. */
@Composable
private fun wallpaperTopColor(bg: String, file: java.io.File): Color? = when {
    bg.startsWith("preset:") -> ChatBackgrounds.preset(bg.removePrefix("preset:"))?.topColor
    bg == "custom" -> {
        // Re-read when the file is replaced, exactly as the wallpaper itself does.
        val stamp = file.lastModified()
        val tone by produceState<Color?>(initialValue = null, stamp) {
            value = withContext(Dispatchers.IO) { topStripTone(file) }
        }
        tone
    }
    else -> null
}

/** Mean colour of the top eighth of a custom wallpaper — the strip the header
 *  covers. Decoded at 1/16 scale on purpose: the only question being asked is
 *  "light or dark", and a thumbnail answers it exactly as well as a
 *  twelve-megapixel photo while costing nothing to hold. */
private fun topStripTone(file: java.io.File): Color? = runCatching {
    val opts = BitmapFactory.Options().apply { inSampleSize = 16 }
    val bmp = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return@runCatching null
    val rows = (bmp.height / 8).coerceIn(1, bmp.height)
    val px = IntArray(bmp.width * rows)
    bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, rows)
    bmp.recycle()
    var r = 0L
    var g = 0L
    var b = 0L
    for (p in px) {
        r += (p shr 16) and 0xFF
        g += (p shr 8) and 0xFF
        b += p and 0xFF
    }
    if (px.isEmpty()) null else Color((r / px.size).toInt(), (g / px.size).toInt(), (b / px.size).toInt())
}.getOrNull()

/** Settings picker: None + built-in presets + a custom image from the gallery.
 *  Global wallpaper (applies to all chats). */
@Composable
internal fun ChatBackgroundScreen(onBack: () -> Unit) = BackgroundPickerScreen(
    stringResource(R.string.settings_row_chat_bg), LocalStores.chatBackground,
    onSelect = { LocalStores.setChatBackground(it) },
    onSaveImage = { ctx, bytes -> LocalStores.saveChatBackgroundImage(ctx, bytes) },
    onBack,
)

/** Same picker for the HOME / chat-list wallpaper (separate selection). */
@Composable
internal fun HomeBackgroundScreen(onBack: () -> Unit) = BackgroundPickerScreen(
    stringResource(R.string.settings_row_home_bg), LocalStores.homeBackground,
    onSelect = { LocalStores.setHomeBackground(it) },
    onSaveImage = { ctx, bytes -> LocalStores.saveHomeBackgroundImage(ctx, bytes) },
    onBack,
)

/** How big a picked wallpaper is kept, in pixels on its longest side.
 *
 *  ⚠ NOT the avatar's 640 (#725, filed with a photo that arrived smeared).
 *  A wallpaper is drawn [ContentScale.Crop] across the whole window, so 640
 *  had to be blown up three to four times to cover an ordinary 1080 x 2400
 *  screen. At 1920 the same photo covers that screen from a downscale on the
 *  width and about 1.25x on the height, which is the difference between
 *  "sharp" and "obviously resampled".
 *
 *  ⚠ And not larger than that either. [WallpaperBackground] decodes this file
 *  with a plain `decodeFile`, no subsampling, and holds the bitmap for as long
 *  as the screen is up, for BOTH selections at once when the chat and the
 *  home list each have one. 1920 costs at most 1920 x 1920 x 4 = 14.7 MB
 *  decoded (a 4:3 photo, 1440 x 1920, is 11 MB); the 2560 the full-screen
 *  photo viewer uses would be 26 MB apiece, and those bitmaps are transient
 *  while a wallpaper is resident. */
private const val WALLPAPER_MAX_SIDE = 1920

/** JPEG quality for a picked wallpaper. Above the avatar's 85 because a
 *  wallpaper is mostly large flat gradients seen full size, where 85 bands
 *  visibly; the file lives on this device only and never goes on the wire, so
 *  the extra few hundred KB buys the banding away for nothing. */
private const val WALLPAPER_QUALITY = 92

@Composable
private fun BackgroundPickerScreen(
    title: String,
    selectedFlow: kotlinx.coroutines.flow.StateFlow<String>,
    onSelect: (String) -> Unit,
    onSaveImage: (android.content.Context, ByteArray) -> Unit,
    onBack: () -> Unit,
) {
    val c = RcqTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val selected by selectedFlow.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                compressImageFor(context, uri, maxSide = WALLPAPER_MAX_SIDE, quality = WALLPAPER_QUALITY)
            }
            if (bytes != null) onSaveImage(context, bytes)
        }
    }
    // The built-in gradients that suit the theme in use, plus whatever is
    // already chosen. See [ChatBackgrounds.offered] for why the selected one is
    // never filtered away.
    val offered = ChatBackgrounds.offered(c.isDark, selected.removePrefix("preset:").takeIf { selected.startsWith("preset:") })
    Column(Modifier.fillMaxSize().background(c.bgPrimary)) {
        SettingsTopBar(title, onBack)
        // Said out loud, because a shorter grid than last time reads as
        // wallpapers having gone missing rather than as a shorter list on
        // purpose. Custom images are not filtered and the note does not claim
        // they are.
        Text(
            stringResource(R.string.chat_bg_theme_set_note),
            color = c.textSecondary, fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                BgTile(stringResource(R.string.chat_bg_none), selected == "",
                    swatch = Modifier.background(c.bgSecondary)) { onSelect("") }
            }
            item {
                BgTile(stringResource(R.string.chat_bg_custom), selected == "custom",
                    swatch = Modifier.background(c.bgSecondary), icon = Icons.Filled.PhotoLibrary) { picker.launch("image/*") }
            }
            items(offered) { p ->
                BgTile(p.label, selected == "preset:${p.id}",
                    swatch = Modifier.background(p.brush)) { onSelect("preset:${p.id}") }
            }
        }
    }
}

@Composable
private fun BgTile(
    label: String,
    selected: Boolean,
    swatch: Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: () -> Unit,
) {
    val c = RcqTheme.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.72f).clip(RoundedCornerShape(12.dp)).then(swatch)
                .then(if (selected) Modifier.border(2.5.dp, c.accent, RoundedCornerShape(12.dp)) else Modifier)
                .clickable { onClick() },
            contentAlignment = Alignment.Center,
        ) {
            icon?.let { Icon(it, null, tint = c.textPrimary) }
            if (selected) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(6.dp).clip(CircleShape).background(c.accent),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.padding(2.dp)) }
            }
        }
        Box(Modifier.height(4.dp))
        Text(label, color = c.textPrimary, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
