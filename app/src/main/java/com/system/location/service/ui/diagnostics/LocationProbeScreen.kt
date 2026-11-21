package com.system.location.service.ui.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.system.location.service.R

@Composable
internal fun LocationProbeScreen(status: String, report: String, onBack: () -> Unit,
    onStart: () -> Unit, onStop: () -> Unit, onCopy: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(Modifier.safeDrawingPadding(), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { TextButton(onClick = onBack) { Text(stringResource(R.string.probe_back)) } }
            item { Text(stringResource(R.string.probe_title), style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() }) }
            item { Text(stringResource(R.string.probe_description), style = MaterialTheme.typography.bodyMedium) }
            item { Text(status, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            item { Button(onClick = onStart, Modifier.fillMaxWidth()) { Text(stringResource(R.string.probe_start)) } }
            item { OutlinedButton(onClick = onStop, Modifier.fillMaxWidth()) { Text(stringResource(R.string.probe_stop)) } }
            item { TextButton(onClick = onCopy, Modifier.fillMaxWidth()) { Text(stringResource(R.string.probe_copy)) } }
            item { SelectionContainer { Text(report, fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall) } }
        }
    }
}
