package com.machinepulse.edge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.machinepulse.edge.ui.MachinePulseApp
import com.machinepulse.edge.ui.theme.MachinePulseTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MachinePulseTheme {
                MachinePulseApp()
            }
        }
    }
}
