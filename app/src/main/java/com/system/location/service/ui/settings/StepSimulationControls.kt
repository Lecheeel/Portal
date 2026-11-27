package com.system.location.service.ui.settings

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.system.location.service.R
import com.system.location.service.core.backend.BackendResult
import com.system.location.service.core.sensors.StepSimulationMethod
import com.system.location.service.core.sensors.StepSimulationSettings
import com.system.location.service.data.persistence.StepSimulationPreferences
import com.system.location.service.designsystem.components.PortalActionRow
import com.system.location.service.designsystem.components.PortalSettingGroup
import com.system.location.service.designsystem.components.PortalSwitchRow
import com.system.location.service.ext.sensorCadenceScale
import com.system.location.service.ext.sensorVariation
import com.system.location.service.ext.sharedPrefs
import com.system.location.service.runtime.ScenarioRuntime
import com.system.location.service.ui.showPortalChoiceDialog
import kotlinx.coroutines.launch

private enum class StepNumber(val title: Int, val unit: Int, val range: Int) {
    CADENCE(R.string.step_fixed_cadence, R.string.step_per_minute, R.string.step_fixed_cadence_range),
    SCALE(R.string.step_cadence_scale, R.string.step_scale_unit, R.string.step_cadence_scale_range),
    VARIATION(R.string.step_variation, R.string.step_variation_unit, R.string.step_variation_range),
}

@Composable
internal fun StepSimulationControls(revision: Int, onTuningChanged: () -> Unit) {
    val context = LocalContext.current
    val settings = remember(context, revision) { StepSimulationPreferences.read(context.sharedPrefs.all) }
    val scale = remember(context, revision) { context.sensorCadenceScale }
    val variation = remember(context, revision) { context.sensorVariation }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var edit by rememberSaveable { mutableStateOf<StepNumber?>(null) }

    fun change(update: (StepSimulationSettings) -> StepSimulationSettings) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val result = ScenarioRuntime.updateStepSimulation(update).await()
                if (result is BackendResult.Failure) {
                    Toast.makeText(context, "${result.reason}\n${result.suggestion}", Toast.LENGTH_LONG).show()
                }
            } finally { busy = false }
        }
    }

    val methods = listOf(stringResource(R.string.step_method_xposed), stringResource(R.string.step_method_native))
    val methodTitle = stringResource(R.string.step_method)
    PortalSettingGroup(stringResource(R.string.step_simulation), if (settings.automatic) 5 else 4) { index, modifier ->
        when (index) {
            0 -> PortalSwitchRow(stringResource(R.string.step_enable),
                subtitle = stringResource(R.string.step_supported_modes),
                checked = settings.enabled, modifier = modifier,
                onCheckedChange = if (busy) null else { enabled -> change { it.copy(enabled = enabled) } })
            1 -> PortalActionRow(methodTitle,
                subtitle = stringResource(if (settings.method == StepSimulationMethod.XPOSED)
                    R.string.step_method_xposed_details else R.string.step_method_native_details),
                status = methods[settings.method.ordinal], enabled = !busy, modifier = modifier,
                onClick = {
                    showPortalChoiceDialog(context, methodTitle, methods, settings.method.ordinal) { selected ->
                        change { it.copy(method = StepSimulationMethod.entries[selected]) }
                    }
                })
            2 -> PortalSwitchRow(stringResource(R.string.step_auto),
                subtitle = stringResource(R.string.step_auto_details),
                checked = settings.automatic, modifier = modifier,
                onCheckedChange = if (busy) null else { automatic -> change { it.copy(automatic = automatic) } })
            3 -> if (settings.automatic) {
                PortalActionRow(stringResource(R.string.step_cadence_scale),
                    status = stringResource(R.string.step_factor_value, scale), enabled = !busy,
                    onClick = { edit = StepNumber.SCALE }, modifier = modifier)
            } else {
                PortalActionRow(stringResource(R.string.step_fixed_cadence),
                    status = stringResource(R.string.step_frequency_value, settings.manualStepsPerMinute), enabled = !busy,
                    onClick = { edit = StepNumber.CADENCE }, modifier = modifier)
            }
            4 -> PortalActionRow(stringResource(R.string.step_variation), status = stringResource(R.string.step_factor_value, variation),
                enabled = !busy, onClick = { edit = StepNumber.VARIATION }, modifier = modifier)
        }
    }

    edit?.let { number ->
        val initial = when (number) {
            StepNumber.CADENCE -> settings.manualStepsPerMinute.toString()
            StepNumber.SCALE -> stringResource(R.string.step_factor_value, scale)
            StepNumber.VARIATION -> stringResource(R.string.step_factor_value, variation)
        }
        val range = stringResource(number.range)
        NumberSettingDialog(stringResource(number.title), stringResource(number.unit), range,
            decimal = number != StepNumber.CADENCE, initial = initial, onDismiss = { edit = null }) { raw ->
            val decimal = raw.replace(',', '.').toDoubleOrNull()
            val fixed = raw.toIntOrNull()
            when (number) {
                StepNumber.CADENCE -> {
                    if (fixed == null || fixed !in 1..StepSimulationSettings.MAX_STEPS_PER_MINUTE) return@NumberSettingDialog range
                    change { it.copy(manualStepsPerMinute = fixed) }
                }
                StepNumber.SCALE -> {
                    if (decimal == null || decimal !in 0.5..2.0) return@NumberSettingDialog range
                    context.sensorCadenceScale = decimal
                    onTuningChanged()
                }
                StepNumber.VARIATION -> {
                    if (decimal == null || decimal !in 0.0..0.5) return@NumberSettingDialog range
                    context.sensorVariation = decimal
                    onTuningChanged()
                }
            }
            null
        }
    }
}
