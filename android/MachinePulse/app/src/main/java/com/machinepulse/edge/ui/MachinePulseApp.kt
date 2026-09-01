package com.machinepulse.edge.ui

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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.machinepulse.edge.ui.theme.MachinePulseTheme

@Composable
fun MachinePulseApp() {
    val state = remember { HomeUiState() }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        MachinePulseHome(
            state = state,
            modifier = Modifier.padding(innerPadding),
        )
    }
}

@Composable
private fun MachinePulseHome(
    state: HomeUiState,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item {
            Header()
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
                WorkflowStep(
                    number = "01",
                    title = "Learn baseline",
                    detail = "No baseline sessions recorded",
                    enabled = state.sensorCaptureReady,
                    icon = Icons.Default.Settings,
                )
                WorkflowStep(
                    number = "02",
                    title = "Scan machine",
                    detail = "Available after a baseline is learned",
                    enabled = state.baselineReady,
                    icon = Icons.Default.PlayArrow,
                )
            }
        }
        item {
            ReadinessPanel()
        }
        item {
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun Header() {
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
        StatusLine()
    }
}

@Composable
private fun StatusLine() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.semantics(mergeDescendants = true) {
            contentDescription = "Setup status. Sensor capture not connected."
        },
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.tertiary),
        )
        Text(
            text = "SETUP | SENSOR CAPTURE NOT CONNECTED",
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
            Button(
                onClick = {},
                enabled = enabled,
                shape = RoundedCornerShape(6.dp),
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { role = Role.Button },
            ) {
                Icon(icon, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(if (number == "01") "Learn" else "Scan")
            }
        }
    }
}

@Composable
private fun ReadinessPanel() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "M1 | APP SHELL",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Capture controls unlock only after sensor recording is implemented and verified.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
        MachinePulseHome(HomeUiState())
    }
}
