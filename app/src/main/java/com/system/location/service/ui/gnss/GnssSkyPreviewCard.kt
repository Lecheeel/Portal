package com.system.location.service.ui.gnss

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.system.location.service.core.gnss.GnssOrbitSourceKind
import com.system.location.service.designsystem.components.PortalCard
import com.system.location.service.designsystem.components.PortalSectionTitle
import com.system.location.service.designsystem.theme.PortalSpacing
import com.system.location.service.ui.viewmodel.GnssSkyPreviewViewModel
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun GnssSkyPreviewCard(model: GnssSkyPreviewViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    var details by remember { mutableStateOf(false) }
    PortalCard {
        Column(verticalArrangement = Arrangement.spacedBy(PortalSpacing.sm)) {
            PortalSectionTitle("模拟目标天空")
            Text(state.message, style = MaterialTheme.typography.bodySmall)
            state.resourceWarning?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            state.frame?.let { frame ->
                Text(String.format(Locale.getDefault(), "目标：%.5f, %.5f · 高程 %.1f m",
                    frame.observer.latitude, frame.observer.longitude, frame.altitudeMeters),
                    style = MaterialTheme.typography.bodyMedium)
                Text("目标时间：${DateFormat.getDateTimeInstance().format(Date(frame.generatedAtMillis))}",
                    style = MaterialTheme.typography.bodySmall)
                Text("可见 ${frame.satellites.size} · 跟踪 ${frame.trackedSatelliteCount ?: "未知"} · 在用 ${frame.satellites.count { it.usedInFix }}",
                    style = MaterialTheme.typography.bodyMedium)
                val minimum = frame.requestedMinimumSatellites
                Text(if (minimum == 0) "数量：自动"
                    else "可见下限 $minimum：${if (frame.satellites.size >= minimum) "已达标" else "未达标"}",
                    style = MaterialTheme.typography.bodySmall)
                val sources = frame.orbitProvenance.values.groupingBy { it.kind }.eachCount()
                Text(sources.entries.joinToString(" · ") { (kind, count) -> "${kind.label()} $count" }
                    .ifEmpty { "暂无轨道输出" }, style = MaterialTheme.typography.bodySmall)
                Text(frame.satellites.groupingBy { it.constellation }.eachCount().entries
                    .joinToString(" · ") { "${it.key} ${it.value}" }.ifEmpty { "当前目标无可见卫星" },
                    style = MaterialTheme.typography.bodySmall)
                Text(frame.geometry.dop?.let {
                    String.format(Locale.getDefault(), "HDOP %.2f · VDOP %.2f · PDOP %.2f", it.hdop, it.vdop, it.pdop)
                } ?: "DOP：无有效定位解（${frame.geometry.reason ?: "观测不足"}）",
                    style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { details = !details }) { Text(if (details) "收起来源与诊断" else "查看来源与诊断") }
                if (details) {
                    // Resource-level provenance, not hundreds of duplicate per-spacecraft lines.
                    frame.orbitProvenance.values.distinct().forEach { source ->
                        Text("${source.kind.label()} · ${source.resourceId}\n${source.source}\n参考时刻：${DateFormat.getDateTimeInstance().format(Date(source.referenceEpochMillis))}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    val diagnostics = frame.diagnostics.distinct()
                    diagnostics.take(30).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (diagnostics.size > 30) Text("另有 ${diagnostics.size - 30} 条逐星诊断未展开",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun GnssOrbitSourceKind.label(): String = when (this) {
    GnssOrbitSourceKind.BROADCAST_EPHEMERIS -> "广播星历"
    GnssOrbitSourceKind.ALMANAC -> "历书"
    GnssOrbitSourceKind.OFFLINE_ORBIT -> "离线轨道"
    GnssOrbitSourceKind.VIRTUAL -> "虚拟卫星"
}
