package com.dseu1.miccheck

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.dseu1.miccheck.ui.MicCheckApp
import com.dseu1.miccheck.ui.theme.MicCheckTheme

/** Única pantalla (Activity) de la app. Todo lo visible se dibuja con Compose. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MicCheckTheme {
                MicCheckApp()
            }
        }
    }
}
