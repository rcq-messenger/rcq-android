package app.rcq.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.rcq.android.R
import app.rcq.android.model.UserStatus
import androidx.compose.foundation.Image

/**
 * The iconic ICQ "flower" status icon, rendered from the same PNG set
 * the iOS client ships. The source art is 16×16; high-quality filtering
 * keeps the upscaled glyph clean at row/header sizes (mirrors iOS's
 * `.interpolation(.high)`).
 */
@Composable
fun StatusIcon(status: UserStatus, size: Dp = 28.dp, modifier: Modifier = Modifier, crossIsland: Boolean = false) {
    // Cross-island peer: presence isn't tracked across islands, so instead of
    // claiming online/offline render the ONLINE flower desaturated to gray
    // ("reachable, presence unknown"). Mirrors iOS StatusIcon.
    if (crossIsland) {
        Image(
            bitmap = ImageBitmap.imageResource(R.drawable.status_online),
            contentDescription = "cross-island",
            contentScale = ContentScale.Fit,
            filterQuality = FilterQuality.High,
            colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }),
            modifier = modifier.size(size),
        )
        return
    }
    val res = when (status) {
        UserStatus.ONLINE -> R.drawable.status_online
        UserStatus.AWAY -> R.drawable.status_away
        UserStatus.DND -> R.drawable.status_dnd
        UserStatus.INVISIBLE -> R.drawable.status_invisible
        UserStatus.OFFLINE -> R.drawable.status_offline
    }
    Image(
        bitmap = ImageBitmap.imageResource(res),
        contentDescription = status.label,
        contentScale = ContentScale.Fit,
        filterQuality = FilterQuality.High,
        modifier = modifier.size(size),
    )
}

@Composable
fun StatusDot(status: UserStatus, size: Dp = 9.dp, modifier: Modifier = Modifier) {
    val c = RcqTheme.colors
    val color = when (status) {
        UserStatus.ONLINE -> c.statusOnline
        UserStatus.AWAY -> c.statusAway
        UserStatus.DND -> c.statusBusy
        UserStatus.INVISIBLE -> c.statusInvisible
        UserStatus.OFFLINE -> c.statusOffline
    }
    Box(modifier.size(size).clip(CircleShape).background(color))
}
