package com.system.location.service.ui.gnss

import android.content.Context
import android.location.GnssStatus
import android.location.LocationManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.Fragment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.system.location.service.R
import com.system.location.service.android.widget.SatelliteData
import com.system.location.service.android.widget.SatelliteRadarView
import com.system.location.service.core.backend.BackendType
import com.system.location.service.designsystem.components.PortalCard
import com.system.location.service.designsystem.components.PortalSectionTitle
import com.system.location.service.designsystem.components.PortalSwitchRow
import com.system.location.service.designsystem.theme.PortalSpacing
import com.system.location.service.designsystem.theme.PortalTheme
import com.system.location.service.ext.enableAGPS
import com.system.location.service.ext.enableGetFromLocation
import com.system.location.service.ext.enableNMEA
import com.system.location.service.ext.enableRequestGeofence
import com.system.location.service.runtime.ScenarioRuntime
import com.system.location.service.service.MockServiceHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Compose replacement for the satellite tab. The Radar View remains a custom Android View and is
 * hosted through AndroidView; all surrounding content and controls are Compose.
 */
class GnssComposeFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            PortalTheme {
                GnssScreen(
                    onOpenMock = { enabled, onResult ->
                        lifecycleScope.launch {
                            val result = withContext(Dispatchers.IO) {
                                if (enabled) MockServiceHelper.startGnssMock(requireContext().getSystemService(LocationManager::class.java))
                                else MockServiceHelper.stopGnssMock(requireContext().getSystemService(LocationManager::class.java))
                            }
                            onResult(result)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun GnssScreen(onOpenMock: (Boolean, (Boolean) -> Unit) -> Unit) {
    val context = LocalContext.current
    val runtime by ScenarioRuntime.state.collectAsStateWithLifecycle()
    val locationManager = remember(context) { context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager }
    var satellites by remember { mutableStateOf<List<SatelliteData>>(emptyList()) }
    var mockRunning by remember { mutableStateOf(false) }
    var geofence by remember { mutableStateOf(context.enableRequestGeofence) }
    var fromLocation by remember { mutableStateOf(context.enableGetFromLocation) }
    var agps by remember { mutableStateOf(context.enableAGPS) }
    var nmea by remember { mutableStateOf(context.enableNMEA) }

    val hookReady = runtime.backend != BackendType.MOCK_PROVIDER && runtime.isActive

    LaunchedEffect(locationManager) {
        val manager = locationManager ?: return@LaunchedEffect
        mockRunning = withContext(Dispatchers.IO) {
            runCatching { MockServiceHelper.isGnssMockStart(manager) }.getOrDefault(false)
        }
    }

    DisposableEffect(locationManager) {
        if (locationManager == null) return@DisposableEffect onDispose {}
        val callback = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                val list = buildList {
                    for (index in 0 until status.satelliteCount) {
                        add(
                            SatelliteData(
                                prn = status.getSvid(index),
                                snr = status.getCn0DbHz(index),
                                elevation = status.getElevationDegrees(index),
                                azimuth = status.getAzimuthDegrees(index),
                                hasAlmanac = false,
                                hasEphemeris = false,
                                usedInFix = status.usedInFix(index),
                            ),
                        )
                    }
                }
                satellites = list
            }
        }
        runCatching {
            locationManager.registerGnssStatusCallback(context.mainExecutor, callback)
        }
        onDispose { runCatching { locationManager.unregisterGnssStatusCallback(callback) } }
    }

    val inView = satellites.size
    val inUse = satellites.count { it.usedInFix }
    val averageCn0 = satellites.filter { it.usedInFix }.map { it.snr }.average().takeIf { !it.isNaN() } ?: 0.0
    val gps = satellites.count { it.prn in 1..32 }
    val glonass = satellites.count { it.prn in 65..96 }
    val beidou = satellites.count { it.prn in 201..237 }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            contentPadding = PaddingValues(20.dp, 24.dp, 20.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                PortalCard {
                    PortalSectionTitle("星图信息")
                    Spacer(Modifier.height(PortalSpacing.sm))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AndroidView(
                            factory = { SatelliteRadarView(it, null) },
                            update = { it.setSatellites(satellites) },
                            modifier = Modifier.weight(1f).height(180.dp),
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            GnssMetric("可用卫星数量", "$inView 颗")
                            GnssMetric("在用卫星数量", "$inUse 颗")
                            GnssMetric("平均信号强度", "%.1f dB-Hz".format(averageCn0))
                            Spacer(Modifier.height(4.dp))
                            Text("导航系统", style = MaterialTheme.typography.labelLarge)
                            Text("GPS: $gps   GNS: $glonass   BD: $beidou", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item {
                PortalCard {
                    PortalSectionTitle("GNSS 控制")
                    Spacer(Modifier.height(PortalSpacing.sm))
                    Button(
                        onClick = {
                            if (!mockRunning) {
                                onOpenMock(true) { ok ->
                                    mockRunning = ok
                                    if (!ok) Toast.makeText(context, "模拟 GNSS 服务启动失败", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                onOpenMock(false) { ok ->
                                    if (ok) mockRunning = false
                                    else Toast.makeText(context, "模拟 GNSS 服务停止失败", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        enabled = hookReady,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (mockRunning) "停止模拟" else "开始模拟") }
                    if (!hookReady) {
                        Text(
                            "需要先运行 Xposed/Native 场景。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                PortalCard {
                    PortalSectionTitle("功能设置")
                    PortalSwitchRow(
                        title = "允许地理围栏请求",
                        checked = geofence,
                        onCheckedChange = {
                            geofence = it
                            context.enableRequestGeofence = it
                            Toast.makeText(context, "重启 GNSS 模拟生效", Toast.LENGTH_SHORT).show()
                        },
                    )
                    PortalSwitchRow(
                        title = "允许位置获取",
                        checked = fromLocation,
                        onCheckedChange = {
                            fromLocation = it
                            context.enableGetFromLocation = it
                            Toast.makeText(context, "重启 GNSS 模拟生效", Toast.LENGTH_SHORT).show()
                        },
                    )
                    PortalSwitchRow(
                        title = "启用 AGPS 模块",
                        checked = agps,
                        onCheckedChange = {
                            agps = it
                            context.enableAGPS = it
                            Toast.makeText(context, "重启 GNSS 模拟生效", Toast.LENGTH_SHORT).show()
                        },
                    )
                    PortalSwitchRow(
                        title = "启用 NMEA 模块",
                        checked = nmea,
                        onCheckedChange = {
                            nmea = it
                            context.enableNMEA = it
                            Toast.makeText(context, "重启 GNSS 模拟生效", Toast.LENGTH_SHORT).show()
                        },
                    )
                }
            }
            item {
                Text(
                    "卫星数据和 GNSS/NMEA 行为由系统模块与当前 Xposed/Native 场景共同决定。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun GnssMetric(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}
