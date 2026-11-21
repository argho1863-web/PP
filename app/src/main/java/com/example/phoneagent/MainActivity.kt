package com.example.phoneagent

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import com.example.phoneagent.ui.MainScreen
import com.example.phoneagent.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dark = isSystemInDarkTheme()
            val ctx = LocalContext.current
            val scheme = if (Build.VERSION.SDK_INT >= 31)
                (if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx))
            else (if (dark) darkColorScheme() else lightColorScheme())

            MaterialTheme(colorScheme = scheme) {
                Surface {
                    // Back handling for Settings lives inside SettingsScreen (it saves before leaving).
                    var screen by rememberSaveable { mutableStateOf("main") }
                    if (screen == "main") MainScreen(onOpenSettings = { screen = "settings" })
                    else SettingsScreen(onBack = { screen = "main" })
                }
            }
        }
    }
}
