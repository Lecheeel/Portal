package com.system.location.service.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.system.location.service.core.sensors.SensorTargets
import com.system.location.service.designsystem.theme.PortalTheme

/** Only explicit save updates live preferences. */
class PackageEditorFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            val initial = requireArguments().getStringArrayList("selected").orEmpty().sorted().joinToString("\n")
            setContent {
                PortalTheme {
                    var text by rememberSaveable { mutableStateOf(initial) }
                    var error by rememberSaveable { mutableStateOf<String?>(null) }
                    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("每行填写一个完整包名，支持没有启动图标的应用。清空后保存可关闭传感器模拟。",
                                style = MaterialTheme.typography.bodyMedium)
                            Text("需 Xposed：同时在框架中勾选目标应用，并重启目标应用后生效。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedTextField(text, { text = it; error = null },
                                modifier = Modifier.fillMaxWidth(), minLines = 8,
                                label = { Text("每行一个完整包名") }, isError = error != null,
                                supportingText = { Text(error ?: "最多 128 个应用；返回将放弃未保存的修改") })
                        }
                        Button(onClick = {
                            runCatching { SensorTargets.parse(text) }
                                .onSuccess { selected ->
                                    parentFragmentManager.setFragmentResult(AppSelectionFragment.RESULT, Bundle().apply {
                                        putString("purpose", "sensors")
                                        putStringArrayList("selected", ArrayList(selected))
                                    })
                                    findNavController().popBackStack()
                                }
                                .onFailure { error = "请填写完整包名，最多 128 个" }
                        }, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) { Text("保存包名") }
                    }
                }
            }
        }
}
