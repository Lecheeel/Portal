package com.system.location.service.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue

internal enum class NumberSetting(val title: String, val unit: String, val range: String, val decimal: Boolean = true) {
    ALTITUDE("设置海拔高度", "米", "0–10000 米"),
    ACCURACY("设置精度", "米", "大于 0 且不超过 1000 米"),
    INTERVAL("设置上报间隔", "毫秒", "50–1000 毫秒，整数", false),
    SPEED("设置速度", "米/秒", "0–1000 米/秒"),
    SATELLITES("可见卫星下限", "颗", "0–128，整数；0 表示按所选模式和目标天空自动生成", false),
}

/** The draft belongs to this dialog; only an explicit successful save changes preferences. */
@Composable
internal fun NumberSettingDialog(
    setting: NumberSetting,
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> String?,
) {
    var input by rememberSaveable(setting, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length)))
    }
    var error by rememberSaveable(setting) { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(setting.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it; error = null },
                    label = { Text("数值（${setting.unit}）") },
                    supportingText = { Text(error ?: setting.range) },
                    isError = error != null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = if (setting.decimal) KeyboardType.Decimal else KeyboardType.Number),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                error = onSave(input.text.trim())
                if (error == null) onDismiss()
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
