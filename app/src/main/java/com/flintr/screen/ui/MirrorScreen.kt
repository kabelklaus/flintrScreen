package com.flintr.screen.ui

import androidx.compose.foundation.AndroidEmbeddedExternalSurface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import com.flintr.screen.render.VideoRenderer

/**
 * Full-screen mirrored picture, letterboxed to the stream's aspect ratio. Nothing is drawn
 * on top of it. The surface exists even before mirroring starts, because the sender's
 * first keyframe must not arrive while there is nowhere to decode it to.
 *
 * Uses [AndroidEmbeddedExternalSurface] rather than a raw SurfaceView: a SurfaceView's
 * content is composited by punching a transparent hole through the window, which Compose's
 * own drawing (e.g. the black background below) does not participate in correctly, leaving
 * the decoder rendering into a surface the window never shows. The embedded surface instead
 * renders as a normal layer in Compose's own draw order, so the idle screen drawn above it
 * still covers it correctly while idle.
 */
@Composable
fun MirrorScreen(aspectRatio: Float, mirroring: Boolean, modifier: Modifier = Modifier) {
    val view = LocalView.current
    // TV must not sleep mid-mirror; while idle the screensaver may run (no burn-in).
    SideEffect { view.keepScreenOn = mirroring }
    Box(
        modifier = modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        AndroidEmbeddedExternalSurface(
            modifier = Modifier.aspectRatio(aspectRatio, matchHeightConstraintsFirst = aspectRatio < 1f),
        ) {
            onSurface { surface, _, _ ->
                VideoRenderer.setSurface(surface)
                surface.onDestroyed { VideoRenderer.clearSurface(surface) }
            }
        }
    }
}
