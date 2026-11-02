package com.flintr.screen

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.flintr.screen.render.VideoRenderer
import com.flintr.screen.service.ReceiverService
import com.flintr.screen.ui.MirrorScreen
import kotlinx.coroutines.delay

// Standard Android TV overscan safe-area margins.
private val TvSafeMarginH = 48.dp
private val TvSafeMarginV = 27.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ReceiverService.start(this)
        // No system bars, ever: the mirrored picture (and the ready screen) must fill the TV.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
            val status by ReceiverService.status.collectAsState()
            val mirroring by VideoRenderer.active.collectAsState()
            val aspectRatio by VideoRenderer.aspectRatio.collectAsState()
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    MirrorScreen(aspectRatio, mirroring)
                    // Opaque idle screen covers the surface (and its last frame) until a picture arrives.
                    if (!mirroring) FlintrScreenApp(status)
                    ConnectionToast(
                        status = status,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(horizontal = TvSafeMarginH, vertical = TvSafeMarginV),
                    )
                }
            }
        }
    }
}

private fun readySubtitle(status: ReceiverService.Status) = when (status) {
    ReceiverService.Status.ERROR -> "Bitte Gerät neu starten"
    else -> "Bildschirmsynchronisierung auf deinem Gerät → flintrScreen wählen"
}

@Composable
fun FlintrScreenApp(status: ReceiverService.Status) {
    var showMacHint by remember { mutableStateOf(true) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF05070F)),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = TvSafeMarginH, vertical = TvSafeMarginV)
                .widthIn(max = 920.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MirrorIcon(modifier = Modifier.size(80.dp))
            Spacer(Modifier.height(32.dp))
            Text(
                text = "Bereit zum Teilen",
                color = Color.White,
                textAlign = TextAlign.Center,
                style = TextStyle(
                    fontSize = 72.sp,
                    fontWeight = FontWeight.Bold,
                    shadow = Shadow(
                        color = Color.Black.copy(alpha = 0.6f),
                        offset = Offset(2f, 4f),
                        blurRadius = 16f,
                    ),
                ),
            )
            Spacer(Modifier.height(20.dp))
            Text(
                text = readySubtitle(status),
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 24.sp,
                textAlign = TextAlign.Center,
            )
        }
        if (showMacHint) {
            Text(
                text = "Auf dem Mac: Steuerzentrale → Bildschirmsynchronisierung → " +
                    "flintrScreen → „Inhalte wählen“ → Bildschirm",
                color = Color.White.copy(alpha = 0.45f),
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = TvSafeMarginH, vertical = TvSafeMarginV + 40.dp)
                    .widthIn(max = 760.dp)
                    .focusable()
                    .clickable { showMacHint = false },
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = TvSafeMarginV)
                .alpha(0.35f),
        ) {
            Image(
                painter = painterResource(R.drawable.ic_flintrscreen_logo),
                contentDescription = null,
                modifier = Modifier.height(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "flintrScreen",
                color = Color.White,
                fontSize = 14.sp,
            )
        }
    }
}

/** Simple screen-with-arrow glyph (same idea as AirPlay/Cast): no icon font dependency needed. */
@Composable
private fun MirrorIcon(modifier: Modifier = Modifier, tint: Color = Color.White) {
    Canvas(modifier = modifier) {
        val screenHeight = size.height * 0.62f
        drawRoundRect(
            color = tint,
            size = Size(size.width, screenHeight),
            cornerRadius = CornerRadius(size.width * 0.1f),
            style = Stroke(width = size.width * 0.08f),
        )
        val gap = size.height * 0.08f
        val path = Path().apply {
            moveTo(size.width * 0.5f, screenHeight + gap)
            lineTo(size.width * 0.82f, size.height)
            lineTo(size.width * 0.18f, size.height)
            close()
        }
        drawPath(path, color = tint)
    }
}

/** Brief, auto-dismissing connect/disconnect indicator. The only thing ever drawn over a live picture. */
@Composable
private fun ConnectionToast(status: ReceiverService.Status, modifier: Modifier = Modifier) {
    var message by remember { mutableStateOf<String?>(null) }
    var previous by remember { mutableStateOf(status) }
    LaunchedEffect(status) {
        if (status != previous) {
            message = when {
                status == ReceiverService.Status.CONNECTED -> "Verbunden ✓"
                previous == ReceiverService.Status.CONNECTED -> "Getrennt"
                else -> null
            }
            previous = status
        }
        if (message != null) {
            delay(3000)
            message = null
        }
    }
    if (message != null) {
        Box(
            modifier = modifier
                .background(Color.Black.copy(alpha = 0.55f), shape = RoundedCornerShape(12.dp))
                .padding(horizontal = 20.dp, vertical = 10.dp),
        ) {
            Text(text = message ?: "", color = Color.White, fontSize = 18.sp)
        }
    }
}

@Preview(device = "id:tv_1080p")
@Composable
private fun FlintrScreenAppPreview() {
    FlintrScreenApp(ReceiverService.Status.READY)
}
