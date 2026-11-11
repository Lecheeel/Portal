package com.system.location.service.ui.settings

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.system.location.service.R
import com.system.location.service.designsystem.theme.PortalTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class SelectableApp(val packageName: String, val label: String)

/** Drafts survive recreation; only Save sends a result to the owning settings page. */
class AppSelectionFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            val arguments = requireArguments()
            val initial = arguments.getStringArrayList("selected") ?: arrayListOf()
            val appContext = requireContext().applicationContext
            setContent {
                PortalTheme {
                    var selected by rememberSaveable { mutableStateOf(ArrayList(initial)) }
                    var query by rememberSaveable { mutableStateOf("") }
                    var selectedOnly by rememberSaveable { mutableStateOf(false) }
                    var generation by remember { mutableIntStateOf(0) }
                    var error by remember { mutableStateOf<String?>(null) }
                    var loading by remember { mutableStateOf(true) }
                    var apps by remember { mutableStateOf<List<SelectableApp>>(emptyList()) }
                    LaunchedEffect(generation) {
                        loading = true
                        error = null
                        runCatching {
                            withContext(Dispatchers.IO) {
                                val pm = appContext.packageManager
                                val installed = pm.queryIntentActivities(
                                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0,
                                ).map { it.activityInfo.applicationInfo }
                                    .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
                                    .distinctBy { it.packageName }
                                    .map { SelectableApp(it.packageName, pm.getApplicationLabel(it).toString()) }
                                val visible = installed.map { it.packageName }.toSet()
                                (installed + initial.filterNot { it in visible }.map {
                                    SelectableApp(it, "已保存的包名")
                                }).sortedBy { it.label.lowercase() }
                            }
                        }.onSuccess { apps = it }.onFailure { failure ->
                            if (failure is kotlinx.coroutines.CancellationException) throw failure
                            error = "无法加载应用列表，请重试"
                        }
                        loading = false
                    }
                    val visible = remember(apps, selected, query, selectedOnly) {
                        apps.filter { app ->
                            (!selectedOnly || app.packageName in selected) &&
                                (app.label.contains(query, true) || app.packageName.contains(query, true))
                        }
                    }
                    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                        OutlinedTextField(query, { query = it }, label = { Text("搜索应用名称或包名") },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            FilterChip(selectedOnly, { selectedOnly = !selectedOnly },
                                label = { Text("仅已选 · ${selected.size}") })
                            TextButton(onClick = { selected = arrayListOf() }) { Text("清空选择") }
                        }
                        Text("未保存的修改在返回时放弃", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(vertical = 12.dp)) {
                            when {
                                loading -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                                error != null -> item {
                                    Text(error!!, color = MaterialTheme.colorScheme.error)
                                    TextButton(onClick = { generation++ }) { Text("重新加载") }
                                }
                                visible.isEmpty() -> item { Text("没有符合条件的应用") }
                                else -> items(visible, key = { it.packageName }) { app ->
                                    val checked = app.packageName in selected
                                    ListItem(
                                        headlineContent = { Text(app.label) },
                                        supportingContent = { Text(app.packageName) },
                                        trailingContent = { Checkbox(checked, onCheckedChange = null) },
                                        modifier = Modifier.toggleable(checked, role = Role.Checkbox) { enabled ->
                                            selected = ArrayList(selected.toMutableSet().apply {
                                                if (enabled) add(app.packageName) else remove(app.packageName)
                                            })
                                        },
                                    )
                                }
                            }
                        }
                        val overLimit = arguments.getString("purpose") == "sensors" && selected.size > 128
                        if (overLimit) Text("最多选择 128 个应用", color = MaterialTheme.colorScheme.error)
                        Button(onClick = {
                            parentFragmentManager.setFragmentResult(RESULT, Bundle(arguments).apply {
                                putStringArrayList("selected", selected)
                            })
                            val nav = findNavController()
                            if (nav.previousBackStackEntry?.destination?.id == R.id.nav_point_selection) {
                                nav.popBackStack(R.id.nav_point_selection, true)
                            } else nav.popBackStack()
                        }, enabled = !loading && error == null && !overLimit,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                            Text("保存选择（${selected.size}）")
                        }
                    }
                }
            }
        }

    companion object { const val RESULT = "portal-app-selection" }
}
