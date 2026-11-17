package com.system.location.service.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.system.location.service.core.carrier.CarrierPhoneNumbers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun CarrierPhoneNumberEditor(countryIso: String, mcc: String, mnc: String,
    number: String, city: String, onChange: (String, String) -> Unit) {
    val operator = CarrierPhoneNumbers.chinaOperator(mcc.toIntOrNull() ?: 0, mnc.toIntOrNull() ?: -1, mnc.length)
    var cities by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var choosing by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(countryIso, operator) {
        loading = true
        error = null
        try {
            cities = withContext(Dispatchers.IO) {
                CarrierPhoneNumbers.regions.size
                if (countryIso == "CN" && operator != null)
                    CarrierPhoneNumbers.cities(operator).map { it.name }.distinct() else emptyList()
            }
        } catch (_: Exception) { error = "号码规则加载失败，请手动填写" }
        loading = false
    }
    Text("本机号码", style = MaterialTheme.typography.labelLarge)
    OutlinedTextField(value = number, onValueChange = { value ->
        if (value.length <= 40) onChange(value, "")
    }, label = { Text("模拟手机号码（可选）") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
    if (countryIso == "CN") {
        TextButton(enabled = !loading && cities.isNotEmpty(), onClick = { choosing = true }) {
            Text(if (city in cities) "号段归属城市：$city" else "选择号段归属城市")
        }
        if (operator == null) Text("请先选择中国运营商预设；自定义运营商可手动填写号码。",
            style = MaterialTheme.typography.bodySmall)
        else if (!loading && cities.isEmpty()) Text("离线数据暂无此运营商的城市号段，请手动填写号码。",
            style = MaterialTheme.typography.bodySmall)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = !loading && (countryIso != "CN" || city in cities), onClick = {
            runCatching { CarrierPhoneNumbers.generate(countryIso, city, operator) }
                .onSuccess { onChange(it, city); error = null }
                .onFailure { error = it.message ?: "暂不能生成，请手动填写" }
        }) { Text("随机填充号码") }
        TextButton(enabled = number.isNotEmpty() || city.isNotEmpty(), onClick = { onChange("", ""); error = null }) {
            Text("清空号码")
        }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    Text(if (countryIso == "CN") "按离线号段的原始城市与运营商分配生成；携号转网后归属可能不同。生成后保存，读取时不再随机。"
        else "按国家/地区号码规则生成并保存；没有移动号段规则时使用本地普通号码规则。",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text("号码仅用于模拟，不表示已开通或可接收短信；应用仍需号码读取权限。留空保留原接口行为。",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (choosing) AlertDialog(onDismissRequest = { choosing = false }, title = { Text("选择号段归属城市") }, text = {
        Column {
            OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("搜索省、市或地区") }, singleLine = true)
            LazyColumn(Modifier.height(360.dp)) {
                items(cities.filter { it.contains(query.trim(), true) }, key = { it }) { name ->
                    TextButton(onClick = { onChange("", name); choosing = false }, modifier = Modifier.fillMaxWidth()) {
                        Text(name, Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { choosing = false }) { Text("关闭") } })
}
