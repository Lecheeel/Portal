package com.system.location.service.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.system.location.service.designsystem.components.PortalCard
import com.system.location.service.designsystem.components.PortalSectionTitle
import com.system.location.service.designsystem.theme.PortalTheme
import com.system.location.service.maps.AMapKeySettings
import com.system.location.service.maps.MapProvider
import com.system.location.service.maps.TencentKeySettings
import com.system.location.service.privacy.SdkPrivacy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dedicated map-key screen: one place for AMap and Tencent credentials plus the exact package
 * name/SHA1 values required by their consoles. Values are copied explicitly; the key itself stays
 * in encrypted storage and is rendered as a password field.
 */
class MapKeyComposeFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            PortalTheme {
                val context = LocalContext.current
                val applicationSha1 = remember {
                    runCatching { AMapKeySettings.applicationSha1(context) }
                }
                MapKeyScreen(
                    packageName = context.packageName,
                    sha1 = applicationSha1.getOrNull(),
                    sha1Error = applicationSha1.exceptionOrNull()?.message,
                    loadCustomKey = { provider ->
                        runCatching {
                            when (provider) {
                                MapProvider.AMAP -> AMapKeySettings.customKey(context)
                                MapProvider.TENCENT -> TencentKeySettings.customKey(context)
                            }
                        }.getOrNull()
                    },
                    effectiveKey = { provider ->
                        runCatching {
                            when (provider) {
                                MapProvider.AMAP -> AMapKeySettings.effectiveKey(context)
                                MapProvider.TENCENT -> TencentKeySettings.effectiveKey(context)
                            }
                        }.getOrDefault("")
                    },
                    onSave = { provider, value -> save(provider, value) },
                    onCopy = ::copy,
                    onOpen = ::open,
                )
            }
        }
    }

    private fun save(provider: MapProvider, value: String): String? {
        val context = requireContext().applicationContext
        if (value.isBlank()) {
            when (provider) {
                MapProvider.AMAP -> {
                    AMapKeySettings.clearCustomKey(context)
                    if (SdkPrivacy.mapsAccepted) runCatching { AMapKeySettings.apply(context) }
                }
                MapProvider.TENCENT -> TencentKeySettings.clearCustomKey(context)
            }
            if (isAdded) {
                Toast.makeText(requireContext(), "自定义 Key 已清除", Toast.LENGTH_SHORT).show()
                requireActivity().recreate()
            }
            return null
        }
        return runCatching {
            when (provider) {
                MapProvider.AMAP -> {
                    AMapKeySettings.saveCustomKey(context, value)
                    if (SdkPrivacy.mapsAccepted) {
                        SdkPrivacy.initialize(context)
                        check(AMapKeySettings.apply(context)) { "高德 Key 为空，SDK 未应用" }
                    }
                }
                MapProvider.TENCENT -> {
                    TencentKeySettings.saveCustomKey(context, value)
                    check(TencentKeySettings.apply(context)) { "腾讯 Key 为空，未应用" }
                }
            }
            if (isAdded) requireActivity().recreate()
            null
        }.exceptionOrNull()?.message ?: "保存失败，请检查 Key 或稍后重试"
    }

    private fun copy(label: String, value: String) {
        if (!isAdded || value.isBlank()) return
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(requireContext(), "已复制$label", Toast.LENGTH_SHORT).show()
    }

    private fun open(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { if (isAdded) Toast.makeText(requireContext(), "无法打开浏览器", Toast.LENGTH_SHORT).show() }
    }
}

@Composable
private fun MapKeyScreen(
    packageName: String,
    sha1: String?,
    sha1Error: String?,
    loadCustomKey: (MapProvider) -> String?,
    effectiveKey: (MapProvider) -> String,
    onSave: (MapProvider, String) -> String?,
    onCopy: (String, String) -> Unit,
    onOpen: (String) -> Unit,
) {
    var providerName by rememberSaveable { mutableStateOf(MapProvider.AMAP.name) }
    val provider = MapProvider.valueOf(providerName)
    var keyText by rememberSaveable(providerName) { mutableStateOf("") }
    var visible by rememberSaveable { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }

    LaunchedEffect(providerName, revision) {
        keyText = loadCustomKey(provider).orEmpty()
        status = when {
            keyText.isNotBlank() -> "自定义 Key"
            effectiveKey(provider).isNotBlank() -> "内置 Key"
            else -> "未配置"
        }
        error = null
    }

    val validation = remember(providerName, keyText) {
        runCatching {
            if (keyText.isNotBlank()) when (provider) {
                MapProvider.AMAP -> com.system.location.service.maps.AMapKeyPolicy.normalize(keyText)
                MapProvider.TENCENT -> com.system.location.service.maps.TencentKeyPolicy.normalize(keyText)
            }
        }.exceptionOrNull()?.message
    }
    val consoleUrl = when (provider) {
        MapProvider.AMAP -> "https://console.amap.com/dev/key/app"
        MapProvider.TENCENT -> "https://lbs.qq.com/dev/console/application/mine"
    }
    val docsUrl = when (provider) {
        MapProvider.AMAP -> "https://lbs.amap.com/api/android-sdk/guide/create-project/get-key"
        MapProvider.TENCENT -> "https://lbs.qq.com/mobile/androidMapSDK/developerGuide/getKey"
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().widthIn(max = 720.dp),
            contentPadding = PaddingValues(20.dp, 16.dp, 20.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "intro") {
                PortalSectionTitle(
                    "地图 Key",
                    "使用自己的高德或腾讯 Key。包名与 SHA1 可直接复制到申请页面；Key 仅加密保存在本机。",
                )
            }
            item(key = "provider") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MapProvider.entries.forEach { entry ->
                        FilterChip(
                            selected = provider == entry,
                            onClick = { providerName = entry.name },
                            label = { Text(entry.label) },
                        )
                    }
                }
            }
            item(key = "key") {
                PortalCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("当前：$status", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.width(12.dp))
                        AssistChip(onClick = { onOpen(consoleUrl) }, label = { Text("申请 Key") })
                    }
                    Text(
                        if (provider == MapProvider.AMAP) "高德 Key" else "腾讯 Key",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    OutlinedTextField(
                        value = keyText,
                        onValueChange = { keyText = it.trim(); error = null },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        singleLine = true,
                        label = { Text("${provider.label} Key") },
                        placeholder = { Text("留空保存可恢复内置 Key") },
                        isError = validation != null,
                        supportingText = validation?.let { { Text(it) } },
                        visualTransformation = if (visible) VisualTransformation.None
                        else PasswordVisualTransformation(),
                        trailingIcon = {
                            TextButton(onClick = { visible = !visible }) {
                                Text(if (visible) "隐藏" else "显示")
                            }
                        },
                    )
                    Row(
                        modifier = Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            enabled = validation == null,
                            onClick = {
                                error = onSave(provider, keyText)
                                revision++
                            },
                        ) { Text("保存并应用") }
                        if (keyText.isNotBlank()) {
                            TextButton(onClick = {
                                error = onSave(provider, "")
                                revision++
                            }) { Text("清除自定义") }
                        }
                    }
                    error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp))
                    }
                    Text(
                        when (provider) {
                            MapProvider.AMAP ->
                                "高德地图/定位/搜索会在保存后使用新 Key；若地图 SDK 已初始化，页面会重建。"
                            MapProvider.TENCENT ->
                                "腾讯搜索、路线和地址等 WebService 立即使用新 Key；地图 SDK 在新建 MapView 时读取新 Key，保存后会重建页面。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            item(key = "application-info") {
                PortalCard {
                    PortalSectionTitle("申请信息", "将以下值填入 ${provider.label} 控制台")
                    KeyInfoRow("包名", packageName) { onCopy("包名", packageName) }
                    val sha1Value = sha1
                    if (sha1Value.isNullOrBlank()) {
                        Text(
                            sha1Error ?: "无法读取当前安装包签名",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        sha1Value.lines().filter(String::isNotBlank).forEachIndexed { index, line ->
                            val label = when {
                                provider == MapProvider.TENCENT -> "当前安装包 SHA1（腾讯申请不需要）"
                                index == 0 -> "SHA1"
                                else -> "SHA1（签名 ${index + 1}）"
                            }
                            KeyInfoRow(label, line) { onCopy("SHA1", line) }
                        }
                    }
                    Row(
                        modifier = Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextButton(onClick = {
                            val info = buildString {
                                append("包名：$packageName\n")
                                if (provider == MapProvider.AMAP) {
                                    append("SHA1：\n")
                                    append(sha1.orEmpty())
                                } else {
                                    append("腾讯 Android SDK 申请不需要 SHA1")
                                }
                            }
                            onCopy("申请信息", info)
                        }) { Text("复制申请信息") }
                        TextButton(onClick = { onOpen(docsUrl) }) { Text("查看官方文档") }
                    }
                }
            }
            item(key = "steps") {
                PortalCard {
                    PortalSectionTitle("填写步骤")
                    Text(
                        when (provider) {
                            MapProvider.AMAP ->
                                "1. 打开高德控制台并创建应用。\n" +
                                    "2. 添加 Android Key，填写上方包名与 SHA1。\n" +
                                    "3. 将生成的 Key 粘贴到本页并保存。\n" +
                                    "Debug 与 Release 签名通常不同，请分别申请。"
                            MapProvider.TENCENT ->
                                "1. 打开腾讯位置服务控制台并创建应用/Key。\n" +
                                    "2. 勾选地图 SDK（如需搜索/路线，再勾选 WebService API），可选填上方包名。\n" +
                                    "3. 腾讯 Android 申请不需要 SHA1；将生成的 Key 粘贴到本页并保存。\n" +
                                    "保存后新建 MapView 会读取新 Key。"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun KeyInfoRow(label: String, value: String, onCopy: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            SelectionContainer {
                Text(value, style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace)
            }
        }
        TextButton(onClick = onCopy) { Text("复制") }
    }
}
