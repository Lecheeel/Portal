package com.system.location.service.ui.gnss

import android.content.Context
import android.location.GnssStatus
import android.location.LocationManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme

import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.system.location.service.ui.viewmodel.GnssViewModel
import com.system.location.service.ui.viewmodel.GnssSetting
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.system.location.service.R
import com.system.location.service.android.widget.SatelliteData
import com.system.location.service.android.widget.SatelliteRadarView
import com.system.location.service.core.backend.BackendType
import com.system.location.service.designsystem.components.PortalCard
import com.system.location.service.designsystem.components.PortalSectionTitle
import com.system.location.service.designsystem.components.PortalSwitchRow
import com.system.location.service.designsystem.theme.PortalSpacing
import com.system.location.service.designsystem.theme.PortalTheme
import com.system.location.service.runtime.ScenarioRuntime
import kotlinx.coroutines.launch

/**
 * Compose replacement for the satellite tab. The Radar View remains a custom Android View and is
 * hosted through AndroidView; all surrounding content and controls are Compose.
 */
class GnssComposeFragment : Fragment() {
    private val model by viewModels<GnssViewModel>()
    private val captureModel by viewModels<com.system.location.service.ui.viewmodel.GnssCaptureViewModel>()
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            PortalTheme {
                GnssScreen(model, captureModel)
            }
        }
    }
}

@Composable
private fun GnssScreen(model: GnssViewModel, captureModel: com.system.location.service.ui.viewmodel.GnssCaptureViewModel) {
    val context = LocalContext.current
    val runtime by ScenarioRuntime.state.collectAsStateWithLifecycle()
    val locationManager = remember(context) { context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager }
    var satellites by remember { mutableStateOf<List<SatelliteData>>(emptyList()) }
    val control by model.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    val hookReady = runtime.backend != BackendType.MOCK_PROVIDER && runtime.isActive

    LaunchedEffect(locationManager, lifecycle, model) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            coroutineScope {
                launch {
                    ScenarioRuntime.state.map { it.backend to it.phase }.distinctUntilChanged().collect { model.refresh() }
                }
                val manager = locationManager ?: return@coroutineScope
                var listening = true
                val callback = object : GnssStatus.Callback() {
                    override fun onStopped() { if (listening) satellites = emptyList() }

                    override fun onSatelliteStatusChanged(status: GnssStatus) {
                        if (!listening) return
                        val list = buildList {
                            for (index in 0 until status.satelliteCount) {
                                add(
                                    SatelliteData(
                                        prn = status.getSvid(index),
                                        constellationType = status.getConstellationType(index),
                                        snr = status.getCn0DbHz(index),
                                        elevation = status.getElevationDegrees(index),
                                        azimuth = status.getAzimuthDegrees(index),
                                        hasAlmanac = status.hasAlmanacData(index),
                                        hasEphemeris = status.hasEphemerisData(index),
                                        usedInFix = status.usedInFix(index),
                                    ),
                                )
                            }
                        }
                        satellites = list
                    }
                }
                if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    try { manager.registerGnssStatusCallback(context.mainExecutor, callback) }
                    catch (_: SecurityException) { satellites = emptyList() }
                } else satellites = emptyList()
                try { awaitCancellation() }
                finally {
                    listening = false
                    runCatching { manager.unregisterGnssStatusCallback(callback) }
                    satellites = emptyList()
                }
            }
        }
    }


    val inView = satellites.size
    val inUse = satellites.count { it.usedInFix }
    val averageCn0 = satellites.filter { it.usedInFix }.map { it.snr }.average().takeIf { it.isFinite() }
    val systems = satellites.groupingBy { it.constellationType }.eachCount()
        .toSortedMap().map { (type, count) ->
            val name = when (type) {
                GnssStatus.CONSTELLATION_GPS -> "GPS"
                GnssStatus.CONSTELLATION_GLONASS -> "GLONASS"
                GnssStatus.CONSTELLATION_BEIDOU -> "北斗"
                GnssStatus.CONSTELLATION_GALILEO -> "Galileo"
                GnssStatus.CONSTELLATION_QZSS -> "QZSS"
                GnssStatus.CONSTELLATION_SBAS -> "SBAS"
                GnssStatus.CONSTELLATION_IRNSS -> "NavIC"
                else -> "未知系统 ($type)"
            }
            "$name：$count"
        }.joinToString("   ").ifEmpty { "暂无观测" }


    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(PortalSpacing.xl, PortalSpacing.xl, PortalSpacing.xl, 40.dp),
            verticalArrangement = Arrangement.spacedBy(PortalSpacing.lg),
        ) {
            item(key = "sky") {
                PortalCard(modifier = Modifier.animateContentSize()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(painterResource(R.drawable.baseline_satellite_alt_24), null, tint = MaterialTheme.colorScheme.primary)
                        PortalSectionTitle("星图信息")
                    }
                    AndroidView(
                        factory = { SatelliteRadarView(it, null) },
                        update = { it.setSatellites(satellites); it.setActive(lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) },
                        onReset = null,
                        onRelease = { it.setActive(false) },
                        modifier = Modifier.align(Alignment.CenterHorizontally).widthIn(max = 280.dp).fillMaxWidth().aspectRatio(1f),
                    )
                    AnimatedVisibility(visible = satellites.isEmpty()) {
                        Text(
                            "暂无卫星数据",
                            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        GnssMetric("可用卫星", "$inView 颗", Modifier.weight(1f))
                        GnssMetric("在用卫星", "$inUse 颗", Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(PortalSpacing.md))
                    GnssMetric("平均信号强度", averageCn0?.let { "%.1f dB-Hz".format(it) } ?: "— dB-Hz", Modifier.fillMaxWidth())
                    Spacer(Modifier.height(PortalSpacing.lg))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(PortalSpacing.md))
                    Text("导航系统", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(PortalSpacing.xs))
                    Text(systems, style = MaterialTheme.typography.bodyMedium)
                }
            }
            item(key = "capture") { GnssCaptureCard(captureModel, runtime.isActive) }
            item(key = "control") {
                PortalCard(modifier = Modifier.animateContentSize()) {
                    PortalSectionTitle("GNSS 控制")
                    Spacer(Modifier.height(PortalSpacing.sm))
                    Button(
                        onClick = model::toggle,
                        enabled = hookReady && !control.busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        AnimatedContent(targetState = control.running, label = "gnssControl") { running ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(painterResource(if (running) R.drawable.baseline_stop_24 else R.drawable.rounded_play_arrow_24), null, Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(if (running) "停止模拟" else "开始模拟")
                            }
                        }
                    }
                    control.message?.let { message ->
                        Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                    }
                    AnimatedVisibility(visible = !hookReady) {
                        Text(
                            "需要先运行 Xposed/Native 场景。",
                            modifier = Modifier.padding(top = PortalSpacing.sm),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item(key = "settings") {
                PortalCard {
                    PortalSectionTitle("功能设置")
                    PortalSwitchRow(
                        title = "允许地理围栏请求",
                        checked = control.geofence,
                        onCheckedChange = if (control.busy) null else ({ enabled: Boolean ->
                            model.setPreference(GnssSetting.GEOFENCE, enabled)
                        }),
                    )
                    PortalSwitchRow(
                        title = "允许地理编码查询",
                        subtitle = "按坐标或地名查询地址，不控制 Location 定位请求",
                        checked = control.fromLocation,
                        onCheckedChange = if (control.busy) null else ({ enabled: Boolean ->
                            model.setPreference(GnssSetting.FROM_LOCATION, enabled)
                        }),
                    )
                    PortalSwitchRow(
                        title = "允许真实原始 GNSS 数据",
                        subtitle = "透传接收机测量、导航电文和天线信息；与模拟位置可能不一致，不影响围栏和地理编码",
                        checked = control.physicalRawGnss,
                        onCheckedChange = if (control.busy) null else ({ enabled: Boolean ->
                            model.setPreference(GnssSetting.RAW_GNSS, enabled)
                        }),
                    )
                    PortalSwitchRow(
                        title = "启用 NMEA 模块",
                        checked = control.nmea,
                        onCheckedChange = if (control.busy) null else ({ enabled: Boolean ->
                            model.setPreference(GnssSetting.NMEA, enabled)
                        }),
                    )
                }
            }
            item(key = "requirements") {
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
private fun GnssMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge)
        }
    }
}
