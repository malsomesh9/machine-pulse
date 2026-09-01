package com.machinepulse.edge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.machinepulse.edge.capture.MotionCaptureController
import com.machinepulse.edge.ui.MachinePulseApp
import com.machinepulse.edge.ui.theme.MachinePulseTheme

class MainActivity : ComponentActivity() {
    private lateinit var motionCaptureController: MotionCaptureController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        motionCaptureController = MotionCaptureController(applicationContext)
        setContent {
            MachinePulseTheme {
                MachinePulseApp(motionCaptureController)
            }
        }
    }

    override fun onStop() {
        motionCaptureController.stopForLifecycle()
        super.onStop()
    }

    override fun onDestroy() {
        motionCaptureController.close()
        super.onDestroy()
    }
}
