package com.machinepulse.edge.ui

import android.Manifest
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.machinepulse.edge.capture.BaselineComparison
import com.machinepulse.edge.capture.CaptureMode
import com.machinepulse.edge.capture.MotionCaptureController
import com.machinepulse.edge.capture.MotionCapturePhase
import com.machinepulse.edge.capture.MotionCaptureUiState
import com.machinepulse.edge.ui.theme.MachinePulseTheme
import java.util.Locale

@Composable
fun MachinePulseApp(motionCaptureController: MotionCaptureController) {
    val context = LocalContext.current
    val captureState = motionCaptureController.uiState
    val permissionCaptureMode = remember { mutableStateOf(CaptureMode.BASELINE) }
    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        motionCaptureController.refreshMicrophonePermission()
        if (granted) {
            if (permissionCaptureMode.value == CaptureMode.BASELINE) {
                motionCaptureController.prepareBaselineCapture()
            } else {
                motionCaptureController.prepareObservationCapture()
            }
        } else {
            motionCaptureController.reportMicrophonePermissionDenied()
        }
    }
    LaunchedEffect(Unit) {
        motionCaptureController.refreshMicrophonePermission()
    }
    val startCombinedCapture: (CaptureMode) -> Unit = { mode ->
        if (motionCaptureController.hasMicrophonePermission()) {
            if (mode == CaptureMode.BASELINE) {
                motionCaptureController.prepareBaselineCapture()
            } else {
                motionCaptureController.prepareObservationCapture()
            }
        } else {
            permissionCaptureMode.value = mode
            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    val shareLatestSession: () -> Unit = {
        try {
            val sendIntent = motionCaptureController.createLatestSessionShareIntent()
            context.startActivity(Intent.createChooser(sendIntent, "Export MachinePulse session"))
        } catch (error: Exception) {
            Toast.makeText(
                context,
                error.message ?: "Session export failed.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }
    val state = HomeUiState(
        baselineSessionCount = captureState.baselineSessionCount,
        sensorCaptureReady = captureState.sensorAvailable,
        audioCaptureReady = true,
    )
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        MachinePulseHome(
            state = state,
            captureState = captureState,
            onStartMotionCapture = { startCombinedCapture(CaptureMode.BASELINE) },
            onStartScanCapture = { startCombinedCapture(CaptureMode.OBSERVATION) },
            onCancelMotionCapture = motionCaptureController::cancelCapture,
            onShareLatestSession = shareLatestSession,
            modifier = Modifier.padding(innerPadding),
        )
    }
}

@Composable
private fun MachinePulseHome(
    state: HomeUiState,
    captureState: MotionCaptureUiState,
    onStartMotionCapture: () -> Unit,
    onStartScanCapture: () -> Unit,
    onCancelMotionCapture: () -> Unit,
    onShareLatestSession: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item {
            Header(captureState)
        }
        item {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SectionLabel("ACTIVE MACHINE")
                MachinePanel(state)
            }
        }
        item {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SectionLabel("WORKFLOW")
                val baselineActive = captureState.isActive &&
                    captureState.captureMode == CaptureMode.BASELINE
                val scanActive = captureState.isActive &&
                    captureState.captureMode == CaptureMode.OBSERVATION
                WorkflowStep(
                    number = "01",
                    title = "Learn baseline",
                    detail = baselineDetail(captureState),
                    enabled = state.sensorCaptureReady && (!captureState.isActive || baselineActive),
                    icon = if (baselineActive) Icons.Default.Close else Icons.Default.Settings,
                    actionLabel = if (baselineActive) "Cancel capture" else "Learn",
                    progress = if (captureState.isCapturing && baselineActive) {
                        captureState.elapsedMillis.toFloat() / captureState.targetDurationMillis
                    } else {
                        null
                    },
                    onClick = if (baselineActive) onCancelMotionCapture else onStartMotionCapture,
                )
                WorkflowStep(
                    number = "02",
                    title = "Scan machine",
                    detail = if (state.baselineReady) {
                        scanDetail(captureState)
                    } else {
                        "Record 3 accepted baselines first"
                    },
                    enabled = state.scanReady && (!captureState.isActive || scanActive),
                    icon = if (scanActive) Icons.Default.Close else Icons.Default.PlayArrow,
                    actionLabel = if (scanActive) "Cancel scan" else "Scan",
                    progress = if (captureState.isCapturing && scanActive) {
                        captureState.elapsedMillis.toFloat() / captureState.targetDurationMillis
                    } else {
                        null
                    },
                    onClick = if (scanActive) onCancelMotionCapture else onStartScanCapture,
                )
            }
        }
        captureState.latestComparison?.let { comparison ->
            item {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SectionLabel("LATEST RESULT")
                    ScanResultPanel(comparison)
                }
            }
        }
        item {
            ReadinessPanel(captureState, onShareLatestSession)
        }
        item {
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun Header(captureState: MotionCaptureUiState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "MachinePulse Edge",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "AI stethoscope for machines",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        StatusLine(captureState)
    }
}

@Composable
private fun StatusLine(captureState: MotionCaptureUiState) {
    val statusText = when (captureState.phase) {
        MotionCapturePhase.PREPARING -> if (captureState.captureMode == CaptureMode.BASELINE) {
            "GET READY | BASELINE IN ${captureState.countdownSeconds}"
        } else {
            "GET READY | SCAN IN ${captureState.countdownSeconds}"
        }
        MotionCapturePhase.CAPTURING -> if (captureState.captureMode == CaptureMode.BASELINE) {
            "LEARNING | AUDIO + MOTION"
        } else {
            "SCANNING | AUDIO + MOTION"
        }
        MotionCapturePhase.UNAVAILABLE -> "BLOCKED | ACCELEROMETER UNAVAILABLE"
        MotionCapturePhase.ERROR -> "ATTENTION | CAPTURE ERROR"
        MotionCapturePhase.READY,
        MotionCapturePhase.COMPLETE,
        -> if (captureState.microphonePermissionGranted) {
            "READY | AUDIO + MOTION"
        } else {
            "READY | MICROPHONE PERMISSION NEEDED"
        }
    }
    val statusColor = when (captureState.phase) {
        MotionCapturePhase.CAPTURING,
        MotionCapturePhase.COMPLETE,
        -> MaterialTheme.colorScheme.primary
        MotionCapturePhase.PREPARING,
        MotionCapturePhase.READY -> MaterialTheme.colorScheme.secondary
        MotionCapturePhase.ERROR,
        MotionCapturePhase.UNAVAILABLE,
        -> MaterialTheme.colorScheme.error
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.semantics(mergeDescendants = true) {
            contentDescription = statusText.lowercase(Locale.US)
        },
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(statusColor),
        )
        Text(
            text = statusText,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun MachinePanel(state: HomeUiState) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = state.machineName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = state.machineType,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BaselineCount(state.baselineSessionCount)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Add machine")
                }
                FilledTonalButton(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Text("Select machine")
                }
            }
        }
    }
}

@Composable
private fun BaselineCount(count: Int) {
    Column(horizontalAlignment = Alignment.End) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "BASELINES",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WorkflowStep(
    number: String,
    title: String,
    detail: String,
    enabled: Boolean,
    icon: ImageVector,
    actionLabel: String,
    progress: Float? = null,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = number,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Button(
                onClick = onClick,
                enabled = enabled,
                shape = RoundedCornerShape(6.dp),
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { role = Role.Button },
            ) {
                Icon(icon, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(actionLabel)
            }
        }
    }
}

@Composable
private fun ScanResultPanel(comparison: BaselineComparison) {
    val resultColor = if (comparison.outOfBaseline) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, resultColor),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = if (comparison.outOfBaseline) {
                            "OUT OF BASELINE"
                        } else {
                            "BASELINE MATCH"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = resultColor,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = comparison.primaryExplanation,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = comparison.score.toString(),
                        style = MaterialTheme.typography.titleLarge,
                        color = resultColor,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "SCORE",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ResultMetric("Acoustic RMS", comparison.audioRmsDeltaPercent)
            ResultMetric("Vibration energy", comparison.vibrationDeltaPercent)
            ResultMetric("Spectral proxy", comparison.spectralProxyDeltaPercent)
        }
    }
}

@Composable
private fun ResultMetric(label: String, deltaPercent: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = if (deltaPercent >= 0) "+$deltaPercent%" else "$deltaPercent%",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ReadinessPanel(
    captureState: MotionCaptureUiState,
    onShareLatestSession: () -> Unit,
) {
    val detail = captureState.errorMessage
        ?: captureState.latestSummary
        ?: captureState.sensorName?.let {
            if (captureState.microphonePermissionGranted) {
                "Combined capture ready: $it + 44.1 kHz microphone"
            } else {
                "Tap Learn to allow microphone access and begin combined capture."
            }
        }
        ?: "Accelerometer capture is unavailable on this phone."
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "PROTOTYPE | ON-DEVICE SCREENING",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (captureState.baselineSessionCount > 0 && !captureState.isActive) {
            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                onClick = onShareLatestSession,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(6.dp),
            ) {
                Icon(Icons.Default.Share, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Export latest")
            }
        }
    }
}

private fun baselineDetail(captureState: MotionCaptureUiState): String {
    return when {
        captureState.captureMode == CaptureMode.BASELINE &&
            captureState.phase == MotionCapturePhase.PREPARING -> {
            "Place phone flat and release it | starts in ${captureState.countdownSeconds} s"
        }
        captureState.captureMode == CaptureMode.BASELINE && captureState.isCapturing -> {
            val elapsed = String.format(Locale.US, "%.1f", captureState.elapsedMillis / 1_000.0)
            val target = String.format(Locale.US, "%.1f", captureState.targetDurationMillis / 1_000.0)
            "Recording motion $elapsed / $target s | ${captureState.sampleCount} samples"
        }
        captureState.baselineSessionCount == 0 -> "No combined baseline sessions recorded"
        else -> "${captureState.baselineSessionCount} combined baseline session(s) saved"
    }
}

private fun scanDetail(captureState: MotionCaptureUiState): String {
    return when {
        captureState.captureMode == CaptureMode.OBSERVATION &&
            captureState.phase == MotionCapturePhase.PREPARING -> {
            "Place phone flat | scan starts in ${captureState.countdownSeconds} s"
        }
        captureState.captureMode == CaptureMode.OBSERVATION && captureState.isCapturing -> {
            val elapsed = String.format(Locale.US, "%.1f", captureState.elapsedMillis / 1_000.0)
            val target = String.format(Locale.US, "%.1f", captureState.targetDurationMillis / 1_000.0)
            "Comparing observation $elapsed / $target s"
        }
        else -> "Ready for a normal or changed-state observation"
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onBackground,
        fontWeight = FontWeight.Bold,
    )
}

@Preview(showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun MachinePulseHomePreview() {
    MachinePulseTheme {
        MachinePulseHome(
            state = HomeUiState(sensorCaptureReady = true),
            captureState = MotionCaptureUiState(
                phase = MotionCapturePhase.READY,
                sensorName = "Preview accelerometer",
                microphonePermissionGranted = true,
            ),
            onStartMotionCapture = {},
            onStartScanCapture = {},
            onCancelMotionCapture = {},
            onShareLatestSession = {},
        )
    }
}
