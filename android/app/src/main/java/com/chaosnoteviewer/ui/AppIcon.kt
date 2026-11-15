package com.chaosnoteviewer.ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.concurrent.ConcurrentHashMap

// The app's own hand-drawn icons: core/assets/icons/<name>.png, shared with the desktop app (the build packs the
// folder as assets, see app/build.gradle.kts). They are drawn in one color with an alpha channel and recolored by
// [tint], like on desktop.
object AppIcons {
    private val cache = ConcurrentHashMap<String, ImageBitmap>()

    fun load(context: Context, name: String): ImageBitmap = cache.getOrPut(name) {
        context.applicationContext.assets.open("icons/$name.png").use { BitmapFactory.decodeStream(it).asImageBitmap() }
    }
}

@Composable
fun AppIcon(
    name: String,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    size: Dp = 24.dp,
) {
    val context = LocalContext.current
    val bitmap = remember(name) { AppIcons.load(context, name) }
    Icon(bitmap = bitmap, contentDescription = contentDescription, modifier = modifier.size(size), tint = tint)
}
