package com.system.location.service.ui.mock

import android.content.Context
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import com.system.location.service.R
import com.system.location.service.core.location.LocationSanitizer
import com.system.location.service.ui.PortalDialog
import com.system.location.service.ui.PortalDialogContent

internal fun showRockerSpeedInput(context: Context, speed: Double, onSave: (Double) -> Unit): PortalDialog? {
    val dialog = PortalDialog(context, overlay = true)
    val initial = context.getString(R.string.overlay_speed_number, speed)
    dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    dialog.content {
        var input by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
        val value = input.text.trim().replace(',', '.').toDoubleOrNull()
        val valid = value != null && value in 0.0..LocationSanitizer.MAX_SPEED_MPS
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { focus.requestFocus() }
        PortalDialogContent(stringResource(R.string.overlay_speed_edit), body = {
            OutlinedTextField(input, onValueChange = { input = it }, singleLine = true, isError = !valid,
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("rocker_speed_input"),
                label = { Text(stringResource(R.string.overlay_speed_input_range)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        }, buttons = {
            TextButton(onClick = dialog::dismiss) { Text(stringResource(android.R.string.cancel)) }
            TextButton(enabled = valid, onClick = { value?.let(onSave); dialog.dismiss() }) {
                Text(stringResource(android.R.string.ok))
            }
        })
    }
    return try {
        dialog.show()
        dialog
    } catch (_: RuntimeException) {
        dialog.dismiss()
        Toast.makeText(context, R.string.overlay_speed_failed, Toast.LENGTH_SHORT).show()
        null
    }
}
