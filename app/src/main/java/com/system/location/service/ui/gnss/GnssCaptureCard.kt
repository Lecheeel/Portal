package com.system.location.service.ui.gnss

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.system.location.service.designsystem.components.PortalCard
import com.system.location.service.designsystem.components.PortalSectionTitle
import com.system.location.service.designsystem.theme.PortalSpacing
import com.system.location.service.ui.viewmodel.GnssCaptureViewModel
import com.system.location.service.ui.preferenceRevision
import com.system.location.service.ext.minSatelliteCount
import java.util.Locale

@Composable
internal fun GnssCaptureCard(model: GnssCaptureViewModel, simulationActive: Boolean) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val revision = preferenceRevision()
    val minimumSatellites = remember(context, revision) { context.minSatelliteCount }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true) model.capture()
    }
    PortalCard {
        Column(verticalArrangement = Arrangement.spacedBy(PortalSpacing.sm)) {
            PortalSectionTitle("真实卫星采集")
            Text("可选：停止模拟后，在开阔室外采集 60 秒，记录本机的卫星、星座和信号分布，供实测回放及自动模式的信号校准参考。自动和自主模式不依赖采集。数据仅保存在本机。",
                style = MaterialTheme.typography.bodySmall)
            state.profile?.let { profile ->
                Text("${profile.deviceModel} · ${profile.frameCount} 帧 · ${profile.totalSatelliteSamples} 个卫星样本",
                    style = MaterialTheme.typography.bodyMedium)
                Text(profile.constellations.joinToString("\n") { item ->
                    String.format(Locale.getDefault(), "%s：平均 %.1f 颗，占比 %.1f%%，在用 %.1f%%",
                        item.constellation.name, item.meanSatellitesPerFrame, item.ratio * 100, item.usedRatio * 100)
                }, style = MaterialTheme.typography.bodySmall)
            }
            GnssCaptureStatus(model, minimumSatellites)
            if (state.collecting) Text("正在采集：${state.frames} 帧", style = MaterialTheme.typography.bodyMedium)
            Button(
                onClick = {
                    if (state.collecting) model.cancel()
                    else if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) model.capture()
                    else permission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
                },
                enabled = !state.loading && (state.collecting || !simulationActive),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (state.collecting) "取消采集" else "采集真实卫星（60 秒）") }
            state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
