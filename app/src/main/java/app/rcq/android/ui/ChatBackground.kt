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

internal data class ChatBgPreset(val id: String, val label: String, val brush: Brush)

/** Built-in chat wallpapers. Global (one for the whole app), applied behind the
 *  message list in every chat. Founder asked for presets + a custom image. */
internal object ChatBackgrounds {
    val presets = listOf(
        ChatBgPreset("ocean", "Ocean", Brush.verticalGradient(listOf(Color(0xFF1A2980), Color(0xFF26D0CE)))),
        ChatBgPreset("midnight", "Midnight", Brush.verticalGradient(listOf(Color(0xFF0F2027), Color(0xFF203A43), Color(0xFF2C5364)))),
        ChatBgPreset("forest", "Forest", Brush.verticalGradient(listOf(Color(0xFF134E5E), Color(0xFF71B280)))),
        ChatBgPreset("sunset", "Sunset", Brush.verticalGradient(listOf(Color(0xFFFF8008), Color(0xFFFFC837)))),
        ChatBgPreset("lavender", "Lavender", Brush.verticalGradient(listOf(Color(0xFFE0C3FC), Color(0xFF8EC5FC)))),
        ChatBgPreset("rose", "Rose", Brush.verticalGradient(listOf(Color(0xFFFFDEE9), Color(0xFFB5FFFC)))),
        ChatBgPreset("cream", "Cream", SolidColor(Color(0xFFF3EFE7))),
        ChatBgPreset("graphite", "Graphite", Brush.verticalGradient(listOf(Color(0xFF232526), Color(0xFF414345)))),
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
            val bytes = withContext(Dispatchers.IO) { compressImageFor(context, uri) }
            if (bytes != null) onSaveImage(context, bytes)
        }
    }
    Column(Modifier.fillMaxSize().background(c.bgPrimary)) {
        SettingsTopBar(title, onBack)
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
            items(ChatBackgrounds.presets) { p ->
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
