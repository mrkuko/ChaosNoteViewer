package com.chaosnoteviewer

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.chaosnoteviewer.ui.AppNavigation
import com.chaosnoteviewer.ui.theme.ChaosNoteViewerTheme

class MainActivity : ComponentActivity() {
    // set when the app is opened from the widget or a notification, cleared when the screen followed it
    private var launch by mutableStateOf(LaunchRequest())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // a restored activity (rotation) must not follow the same request again
        if (savedInstanceState == null) launch = LaunchRequest.from(intent)
        setContent {
            ChaosNoteViewerTheme {
                AppNavigation(launch = launch, onLaunchHandled = { launch = LaunchRequest() })
            }
        }
    }

    // singleTop: a widget or notification tap while the app is open arrives here
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launch = LaunchRequest.from(intent)
    }
}
