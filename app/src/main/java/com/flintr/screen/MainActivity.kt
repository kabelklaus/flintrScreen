package com.flintr.screen

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.flintr.screen.service.ReceiverService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ReceiverService.start(this)
        setContent {
            val status by ReceiverService.status.collectAsState()
            FlintrScreenApp(status)
        }
    }
}

private fun statusText(status: ReceiverService.Status) = when (status) {
    ReceiverService.Status.CONNECTED -> "flintrScreen — verbunden"
    ReceiverService.Status.ERROR -> "flintrScreen — Empfänger konnte nicht starten"
    ReceiverService.Status.READY, ReceiverService.Status.STOPPED -> "flintrScreen — bereit"
}

@Composable
fun FlintrScreenApp(status: ReceiverService.Status) {
    MaterialTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = statusText(status),
                color = Color.White,
                style = TextStyle(
                    fontSize = 32.sp,
                    shadow = Shadow(
                        color = Color.Black.copy(alpha = 0.6f),
                        offset = androidx.compose.ui.geometry.Offset(2f, 4f),
                        blurRadius = 12f,
                    ),
                ),
            )
        }
    }
}

@Preview(device = "id:tv_1080p")
@Composable
private fun FlintrScreenAppPreview() {
    FlintrScreenApp(ReceiverService.Status.READY)
}
