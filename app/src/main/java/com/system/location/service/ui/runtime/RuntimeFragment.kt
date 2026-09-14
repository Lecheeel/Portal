package com.system.location.service.ui.runtime

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
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
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentRuntimeBinding.bind(view)
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
                        ScenarioRuntime.selectBackend(BackendType.NATIVE)
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
                        binding.runtimeState.text = buildString {
                            appendLine("当前后端：${state.backend}\n状态：${state.phase}")
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
                            "${capabilityLabel(capability)}：${status.availability}\n${status.reason}"
                        }
                    }
                }
                launch {
                    ScenarioRuntime.diagnostics.collect { events ->
                        val migration = com.system.location.service.data.repository.LibraryRepositories.migrationIssues()
                        binding.diagnostics.text = "旧资料迁移：${if (migration.isEmpty()) "无已记录问题" else migration.joinToString("; ")}\n最近诊断\n" + events.takeLast(80).asReversed().joinToString("\n\n") {
                            "${it.backend} · ${it.stage} · ${it.result}\n${it.reason}\n${it.suggestion}"
                        }
                    }
                }
            }
        }
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
