package com.system.location.service.ui.mock

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
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

/** Location form and runtime controls for the simulation screen. */
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
            modifier = Modifier.fillMaxSize().imePadding(),
            contentPadding = PaddingValues(PortalSpacing.xl, PortalSpacing.xl, PortalSpacing.xl, 40.dp),
            verticalArrangement = Arrangement.spacedBy(PortalSpacing.lg),
        ) {
            item(key = "heading") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer) {
                        Icon(
                            painterResource(R.drawable.baseline_my_location_24), null,
                            Modifier.size(48.dp).padding(12.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    Text("坐标定位", style = MaterialTheme.typography.headlineMedium)
                }
            }
            item(key = "coordinates") {
                PortalCard {
                    PortalSectionTitle("WGS84 坐标")
                    Spacer(Modifier.height(PortalSpacing.sm))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("位置名称") },
                        leadingIcon = { Icon(painterResource(R.drawable.baseline_location_on_24), null) },
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
                    Button(onClick = onStart, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Icon(painterResource(R.drawable.rounded_play_arrow_24), null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("开始模拟")
                    }
                    Spacer(Modifier.height(PortalSpacing.sm))
                    OutlinedButton(onClick = onSave, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Icon(painterResource(R.drawable.baseline_save_24), null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("保存至位置库")
                    }
                }
            }
            item(key = "library") {
                OutlinedButton(onClick = onOpenLibrary, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(painterResource(R.drawable.ic_layers_modern), null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("打开资料库")
                }
            }
            item(key = "runtime") {
                AnimatedVisibility(visible = runtime.isActive || runtime.error != null) {
                    PortalCard(modifier = Modifier.animateContentSize()) {
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
                            ) {
                                Icon(painterResource(R.drawable.baseline_stop_24), null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("停止")
                            }
                        }
                    }
                }
            }
            item(key = "message") {
                AnimatedVisibility(visible = message != null) {
                    Text(
                        message.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

        }
    }
}
