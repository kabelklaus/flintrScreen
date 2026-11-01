package com.flintr.screen.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import com.flintr.screen.render.VideoRenderer

/**
 * Full-screen mirrored picture, letterboxed to the stream's aspect ratio. Nothing is drawn
 * on top of it. The surface exists even before mirroring starts, because the sender's
 * first keyframe must not arrive while there is nowhere to decode it to.
 */
@Composable
fun MirrorScreen(aspectRatio: Float, mirroring: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) = VideoRenderer.setSurface(holder.surface)
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {}
                        override fun surfaceDestroyed(holder: SurfaceHolder) = VideoRenderer.clearSurface(holder.surface)
                    })
                }
            },
            // TV must not sleep mid-mirror; while idle the screensaver may run (no burn-in).
            update = { it.keepScreenOn = mirroring },
            modifier = Modifier.aspectRatio(aspectRatio, matchHeightConstraintsFirst = aspectRatio < 1f),
        )
    }
}
