package com.system.location.service.ui.mock

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.fragment.findNavController
import com.system.location.service.R
import com.system.location.service.core.runtime.RuntimePhase
import com.system.location.service.designsystem.components.PortalCard
import com.system.location.service.designsystem.components.PortalSectionTitle
import com.system.location.service.designsystem.theme.PortalSpacing
import com.system.location.service.designsystem.theme.PortalTheme
import com.system.location.service.runtime.ScenarioRuntime
import com.system.location.service.ui.displayLabel
import com.system.location.service.ui.viewmodel.PointViewModel
import java.util.Locale

/**
 * Compose replacement for the location tab of the simulation screen.
 *
 * The SimulationFragment tab host is intentionally kept for this step; the old fragment remains
 * available until the satellite tab and tab shell are migrated.
 */
class MockComposeFragment : Fragment() {
    private val model by viewModels<PointViewModel>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            PortalTheme {
                MockScreen(
                    initialName = model.name,
                    initialLatitude = model.latitude,
                    initialLongitude = model.longitude,
                    onFormChanged = { name, lat, lon ->
                        model.name = name
                        model.latitude = lat
                        model.longitude = lon
                    },
                    onStart = { model.saveOrStart(true) },
                    onSave = { model.saveOrStart(false) },
                    onOpenLibrary = { findNavController().navigate(R.id.nav_route_gallery) },
                    messageFlow = model.message,
                )
            }
        }
    }
}

@Composable
private fun MockScreen(
    initialName: String,
    initialLatitude: String,
    initialLongitude: String,
    onFormChanged: (String, String, String) -> Unit,
    onStart: () -> Unit,
    onSave: () -> Unit,
    onOpenLibrary: () -> Unit,
    messageFlow: kotlinx.coroutines.flow.Flow<String?>,
) {
    val runtime by ScenarioRuntime.state.collectAsStateWithLifecycle()
    val message by messageFlow.collectAsStateWithLifecycle(initialValue = null)
    var name by rememberSaveable { mutableStateOf(initialName) }
    var latitude by rememberSaveable { mutableStateOf(initialLatitude) }
    var longitude by rememberSaveable { mutableStateOf(initialLongitude) }

    LaunchedEffect(name, latitude, longitude) {
        onFormChanged(name, latitude, longitude)
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            contentPadding = PaddingValues(20.dp, 24.dp, 20.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text("坐标定位", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "直接使用 GPS 坐标，无需地图服务。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                PortalCard {
                    PortalSectionTitle("WGS84 坐标")
                    Spacer(Modifier.height(PortalSpacing.sm))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("位置名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(PortalSpacing.sm))
                    OutlinedTextField(
                        value = latitude,
                        onValueChange = { latitude = it },
                        label = { Text("纬度 · -90 至 90") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(PortalSpacing.sm))
                    OutlinedTextField(
                        value = longitude,
                        onValueChange = { longitude = it },
                        label = { Text("经度 · -180 至 180") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(PortalSpacing.md))
                    Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                        Text("开始模拟")
                    }
                    Spacer(Modifier.height(PortalSpacing.sm))
                    OutlinedButton(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
                        Text("保存至位置库")
                    }
                }
            }
            item {
                OutlinedButton(onClick = onOpenLibrary, modifier = Modifier.fillMaxWidth()) {
                    Text("打开资料库")
                }
            }
            if (runtime.isActive || runtime.error != null) {
                item {
                    PortalCard {
                        PortalSectionTitle(
                            "${runtime.backend.displayLabel()} · ${runtime.phase.displayLabel()}",
                            runtime.scenarioName.orEmpty(),
                        )
                        Spacer(Modifier.height(PortalSpacing.sm))
                        runtime.sample?.let { sample ->
                            Text(
                                String.format(Locale.ROOT, "%.6f, %.6f", sample.latitude, sample.longitude),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        runtime.error?.let { error ->
                            Text(
                                "${error.stage}: ${error.reason}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Text(error.suggestion, style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.height(PortalSpacing.sm))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    if (runtime.phase == RuntimePhase.PAUSED) ScenarioRuntime.resume()
                                    else ScenarioRuntime.pause()
                                },
                                enabled = runtime.phase in setOf(RuntimePhase.RUNNING, RuntimePhase.PAUSED),
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(if (runtime.phase == RuntimePhase.PAUSED) "继续" else "暂停")
                            }
                            OutlinedButton(
                                onClick = { ScenarioRuntime.stop() },
                                enabled = runtime.isActive || runtime.error != null,
                                modifier = Modifier.weight(1f),
                            ) { Text("停止") }
                        }
                    }
                }
            }
            message?.let { text ->
                item {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                Text(
                    "坐标越界、名称留空或场景启动失败时，会在下方显示原因。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
