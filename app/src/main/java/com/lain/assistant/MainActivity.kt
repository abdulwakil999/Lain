package com.lain.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.lain.assistant.ui.LainApp
import com.lain.assistant.ui.theme.LainTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as LainApplication).container
        setContent {
            LainTheme {
                LainApp(container = container)
            }
        }
    }
}
