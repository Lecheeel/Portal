package com.system.location.service.ui.gnss

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.system.location.service.core.gnss.GnssCapturePlaybackState
import com.system.location.service.core.gnss.ReceiverGnssSource
import com.system.location.service.ui.viewmodel.GnssCaptureViewModel

/** The preview is local evidence, not a claim about a third-party receiver's current callbacks. */
@Composable
internal fun GnssCaptureStatus(model: GnssCaptureViewModel, minimumSatellites: Int) {
    val flow = remember(model, minimumSatellites) { model.preview(minimumSatellites) }
    val preview by flow.collectAsStateWithLifecycle(initialValue = null)
    Column {
        val current = preview
        if (current == null) {
            Text("正在读取本机画像…", style = MaterialTheme.typography.bodySmall)
        } else when (current.state) {
            GnssCapturePlaybackState.WAITING_FOR_CAPTURE -> Text("等待真实采集；无有效画像时透传真实卫星数据。",
                style = MaterialTheme.typography.bodySmall)
            GnssCapturePlaybackState.INVALID_CAPTURE -> {
                Text("画像不可用；当前透传真实卫星数据，请重新采集。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
                current.issues.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            GnssCapturePlaybackState.READY -> current.frame?.let { frame ->
                val sourceLabel = when (frame.source) {
                    ReceiverGnssSource.STATISTICAL_REPLAY -> "统计回放"
                    ReceiverGnssSource.BROADCAST_EPHEMERIS -> "广播星历几何"
                    ReceiverGnssSource.HYBRID_BROADCAST_REPLAY -> "广播星历几何 + 统计回放"
                }
                Text("采集地点预览（$sourceLabel）：当前有效池 ${frame.availableSatelliteCapacity} 颗，可见 ${frame.satellites.size} 颗。",
                    style = MaterialTheme.typography.bodySmall)
                Text(if (minimumSatellites == 0) {
                    if (frame.source == ReceiverGnssSource.STATISTICAL_REPLAY) "自动：按实测数量分布回放。"
                    else "自动：按采集证据和当前可用几何生成。"
                }
                    else "可见卫星下限 $minimumSatellites 颗${if (frame.satellites.size >= minimumSatellites) "，已满足。" else "，当前无法满足。"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (frame.satellites.size < minimumSatellites) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant)
                frame.diagnostics.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
