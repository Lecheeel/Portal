package com.system.location.service.ui.runtime

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import android.widget.AdapterView
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.google.android.material.button.MaterialButton
import com.system.location.service.core.runtime.DiagnosticEvent
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.system.location.service.R
import com.system.location.service.core.backend.*
import com.system.location.service.core.runtime.RuntimePhase
import com.system.location.service.databinding.FragmentRuntimeBinding
import com.system.location.service.runtime.ScenarioRuntime
import kotlinx.coroutines.launch
import java.util.Locale

class RuntimeFragment : Fragment(R.layout.fragment_runtime) {
    private val expanded = mutableMapOf<String, Boolean>()
    private var clearedAt = 0L
    private var logLimit = 20
    private var filter = 0
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentRuntimeBinding.bind(view)
        clearedAt = savedInstanceState?.getLong("clearedAt") ?: clearedAt
        filter = savedInstanceState?.getInt("filter") ?: filter
        var events = ScenarioRuntime.diagnostics.value
        fun visibleEvents() = events.filter { it.timestamp > clearedAt && (filter == 0 ||
            if (filter == 1) it.result in setOf("FAILED", "ERROR", "REQUIRES_ACTION", "UNAVAILABLE")
            else it.backend == ScenarioRuntime.state.value.backend) }.asReversed()
        fun renderLogs() {
            val visible = visibleEvents()
            binding.toggleLogs.text = "运行日志 · ${visible.size} 条 ${if (binding.logDetails.visibility == View.VISIBLE) "▴" else "▾"}"
            if (binding.logDetails.visibility != View.VISIBLE) return
            val dateFormat = java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            val migration = com.system.location.service.data.repository.LibraryRepositories.migrationIssues()
            binding.diagnostics.text = buildString {
                if (migration.isNotEmpty()) appendLine("资料迁移提示：${migration.joinToString("; ")}\n")
                append(visible.take(logLimit).joinToString("\n\n") {
                    "${dateFormat.format(java.util.Date(it.timestamp))} · ${backendLabel(it.backend)} · ${it.result}\n${it.stage} · ${it.reason}" +
                        if (it.suggestion.isBlank()) "" else "\n建议：${it.suggestion}"
                }.ifBlank { "暂无符合条件的日志" })
            }
            binding.moreLogs.visibility = if (visible.size > logLimit) View.VISIBLE else View.GONE
        }
        fun section(key: String, button: MaterialButton, content: View, title: String) {
            fun update(open: Boolean) {
                expanded[key] = open
                content.visibility = if (open) View.VISIBLE else View.GONE
                button.text = "$title ${if (open) "▴" else "▾"}"
                button.contentDescription = "$title，${if (open) "已展开，点击收起" else "已收起，点击展开"}"
            }
            update(savedInstanceState?.getBoolean(key) ?: expanded[key] ?: false)
            button.setOnClickListener { update(content.visibility != View.VISIBLE); renderLogs() }
        }
        section("state", binding.toggleState, binding.stateDetails, "运行详情")
        section("backend", binding.toggleBackend, binding.backendDetails, "后端设置")
        section("capabilities", binding.toggleCapabilities, binding.capabilityDetails, "能力与诊断")
        section("logs", binding.toggleLogs, binding.logDetails, "运行日志")
        binding.logFilter.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item,
            listOf("全部日志", "异常与待处理", "当前后端"))
        binding.logFilter.setSelection(filter)
        binding.logFilter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                filter = position; logLimit = 20; renderLogs()
            }
        }
        binding.moreLogs.setOnClickListener { logLimit += 20; renderLogs() }
        binding.clearLogs.setOnClickListener { clearedAt = System.currentTimeMillis(); logLimit = 20; renderLogs() }
        binding.copyLogs.setOnClickListener {
            (requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("运行日志", binding.diagnostics.text))
            Toast.makeText(requireContext(), "已复制当前显示的日志", Toast.LENGTH_SHORT).show()
        }
        val types = BackendType.entries
        binding.backend.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item,
            listOf("标准 Mock Provider（无 Root）", "Xposed 系统后端", "Native + Xposed（实验性）"))
        binding.backend.setSelection(types.indexOf(ScenarioRuntime.state.value.backend).coerceAtLeast(0))
        binding.applyBackend.setOnClickListener {
            if (types[binding.backend.selectedItemPosition] == BackendType.NATIVE) {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setTitle("启用实验性 Native 后端")
                    .setMessage("需要 Root 与 Xposed。当前实现使用固定系统库路径、符号和偏移，可能不兼容当前 ROM。停止可禁用行为，卸载 Hook 需要重启系统。不会自动关闭 SELinux。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("明确启用") { _, _ ->
                        requireContext().getSharedPreferences("scenario_runtime", android.content.Context.MODE_PRIVATE)
                            .edit().putBoolean("native_opt_in", true).apply()
                        viewLifecycleOwner.lifecycleScope.launch {
                            val selected = ScenarioRuntime.selectBackend(BackendType.NATIVE).await()
                            Toast.makeText(requireContext(), if (selected) "已应用后端" else "请先停止场景并完成资源清理", Toast.LENGTH_LONG).show()
                        }
                    }.show()
                return@setOnClickListener
            }
            viewLifecycleOwner.lifecycleScope.launch {
                val selected = ScenarioRuntime.selectBackend(types[binding.backend.selectedItemPosition]).await()
                Toast.makeText(requireContext(), if (selected) "已应用后端" else "请先停止场景并完成资源清理", Toast.LENGTH_LONG).show()
            }
        }
        binding.mockSettings.setOnClickListener {
            runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
                .onFailure { Toast.makeText(requireContext(), "请手动打开系统设置中的开发者选项", Toast.LENGTH_LONG).show() }
        }
        binding.pauseResume.setOnClickListener {
            if (ScenarioRuntime.state.value.phase == RuntimePhase.PAUSED) ScenarioRuntime.resume() else ScenarioRuntime.pause()
        }
        binding.stopRuntime.setOnClickListener { ScenarioRuntime.stop() }
        binding.refreshDiagnostics.setOnClickListener { ScenarioRuntime.refreshDiagnostics() }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    ScenarioRuntime.state.collect { state ->
                        binding.phaseTitle.text = when (state.phase) {
                            RuntimePhase.IDLE -> "尚未开始"
                            RuntimePhase.PREPARING -> "准备中"
                            RuntimePhase.READY -> "准备就绪"
                            RuntimePhase.RUNNING -> "正在模拟"
                            RuntimePhase.PAUSED -> "已暂停"
                            RuntimePhase.STOPPING -> "正在停止"
                            RuntimePhase.STOPPED -> "已停止"
                            RuntimePhase.ERROR -> "需要处理"
                        }
                        binding.phaseTitle.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(),
                            when (state.phase) { RuntimePhase.RUNNING -> R.color.green500; RuntimePhase.ERROR -> R.color.red500; else -> R.color.text_primary }))
                        binding.overview.text = "${backendLabel(state.backend)} · ${state.scenarioName ?: "未选择场景"}" +
                            (state.error?.let { "\n${it.reason}\n${it.suggestion}" } ?: "")
                        binding.routeProgress.visibility = if (state.routeId != null) View.VISIBLE else View.GONE
                        binding.routeProgress.progress = (state.progress * 1000).toInt().coerceIn(0, 1000)
                        binding.applyBackend.isEnabled = !state.isActive
                        binding.stopRuntime.isEnabled = state.isActive || state.phase == RuntimePhase.ERROR
                        binding.runtimeState.text = buildString {
                            appendLine("当前后端：${backendLabel(state.backend)}\n状态：${binding.phaseTitle.text}")
                            appendLine("场景：${state.scenarioName ?: "未选择"}\n路线：${state.routeId ?: "—"}")
                            state.sample?.let {
                                appendLine("WGS84：${it.latitude}, ${it.longitude}")
                                appendLine(String.format(Locale.ROOT, "速度：%.2f km/h　进度：%.1f%%", it.speed * 3.6, state.progress * 100))
                            }
                            state.error?.let { appendLine("${it.stage}\n${it.reason}\n${it.suggestion}") }
                        }
                        binding.pauseResume.isEnabled = state.phase in setOf(RuntimePhase.RUNNING, RuntimePhase.PAUSED)
                        binding.pauseResume.text = if (state.phase == RuntimePhase.PAUSED) "恢复" else "暂停"
                        binding.capabilities.text = state.capabilities.entries.joinToString("\n\n") { (capability, status) ->
                            "${capabilityLabel(capability)}：${when (status.availability) {
                                Availability.AVAILABLE -> "可用"; Availability.REQUIRES_ACTION -> "需要设置"
                                Availability.UNAVAILABLE -> "不可用"; Availability.EXPERIMENTAL -> "实验性"
                            }}\n${status.reason}"
                        }
                        renderLogs()
                    }
                }
                launch {
                    ScenarioRuntime.diagnostics.collect { latest ->
                        events = latest
                        renderLogs()
                    }
                }
            }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        expanded.forEach { (key, value) -> outState.putBoolean(key, value) }
        outState.putLong("clearedAt", clearedAt)
        outState.putInt("filter", filter)
    }
    private fun backendLabel(type: BackendType) = when (type) {
        BackendType.MOCK_PROVIDER -> "标准模拟位置"
        BackendType.XPOSED -> "Xposed"
        BackendType.NATIVE -> "Native + Xposed"
    }
    private fun capabilityLabel(capability: Capability) = when (capability) {
        Capability.STANDARD_MOCK -> "标准模拟位置"
        Capability.XPOSED -> "系统 Hook"
        Capability.NATIVE -> "实验性 Native"
        Capability.GMS_FUSED -> "Google 融合定位"
        Capability.BACKGROUND_PLAYBACK -> "后台播放"
        Capability.PER_APP_SCENARIO -> "按应用分配场景"
        Capability.GNSS_INJECTION -> "GNSS 注入"
        Capability.SENSOR_SIMULATION -> "传感器模拟"
    }
}
