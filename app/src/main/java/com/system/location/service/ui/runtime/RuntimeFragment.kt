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
import androidx.core.view.doOnLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.system.location.service.ui.displayStates
import com.system.location.service.ui.setTextIfChanged
import com.system.location.service.ui.displayLabel
import com.system.location.service.R
import com.system.location.service.core.backend.*
import com.system.location.service.core.runtime.RuntimePhase
import com.system.location.service.databinding.FragmentRuntimeBinding
import com.system.location.service.runtime.ScenarioRuntime
import kotlinx.coroutines.launch
import java.util.Locale
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RuntimeFragment : Fragment(R.layout.fragment_runtime) {
    private val exportLog = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val context = context?.applicationContext
        if (uri != null && context != null) lifecycleScope.launch {
            try {
                val report = ScenarioRuntime.diagnosticReport()
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(report) } ?: error("无法写入日志") }
                Toast.makeText(context, "诊断日志已导出", Toast.LENGTH_SHORT).show()
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { Toast.makeText(context, error.message ?: "日志导出失败", Toast.LENGTH_LONG).show() }
        }
    }
    private var environment: RuntimeEnvironment? = null
    private var environmentCheckFailed = false
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
            binding.toggleLogs.setTextIfChanged("运行日志 · ${visible.size} 条")
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
        var renderRuntime: () -> Unit = {}
        fun section(key: String, button: MaterialButton, content: View, title: String) {
            fun update(open: Boolean) {
                expanded[key] = open
                content.visibility = if (open) View.VISIBLE else View.GONE
                button.setTextIfChanged(title)
                button.setIconResource(if (open) R.drawable.baseline_keyboard_arrow_up_24 else R.drawable.baseline_keyboard_arrow_down_24)
                button.contentDescription = "$title，${if (open) "已展开，点击收起" else "已收起，点击展开"}"
            }
            update(savedInstanceState?.getBoolean(key) ?: expanded[key] ?: false)
            button.setOnClickListener {
                val open = content.visibility != View.VISIBLE
                update(open)
                renderRuntime()
                renderLogs()
                if (open) content.doOnLayout {
                    // Reveal the first controls after expanding a section near the bottom edge.
                    val height = minOf(content.height, (240 * resources.displayMetrics.density).toInt())
                    content.requestRectangleOnScreen(android.graphics.Rect(0, 0, content.width, height), true)
                }
            }
        }
        section("state", binding.toggleState, binding.stateDetails, "运行详情")
        section("developer", binding.toggleDeveloper, binding.developerDetails, getString(R.string.runtime_developer_title))
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
        binding.exportLogs.setOnClickListener { exportLog.launch("location-diagnostics.txt") }
        binding.clearLogs.setOnClickListener { clearedAt = System.currentTimeMillis(); logLimit = 20; renderLogs() }
        binding.copyLogs.setOnClickListener {
            (requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("运行日志", binding.diagnostics.text))
            Toast.makeText(requireContext(), "已复制当前显示的日志", Toast.LENGTH_SHORT).show()
        }
        val types = BackendType.entries
        binding.backend.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item,
            listOf(getString(R.string.runtime_mode_mock), getString(R.string.runtime_mode_xposed), getString(R.string.runtime_mode_native)))
        binding.backend.setSelection(types.indexOf(ScenarioRuntime.state.value.backend).coerceAtLeast(0))
        var nativeConfirmed = false
        fun restoreBackendSelection() {
            binding.backend.setSelection(types.indexOf(ScenarioRuntime.state.value.backend).coerceAtLeast(0))
        }
        fun confirmNative() {
            if (childFragmentManager.findFragmentByTag(NativeRiskDialog.TAG) == null) {
                NativeRiskDialog().showNow(childFragmentManager, NativeRiskDialog.TAG)
            }
        }
        childFragmentManager.setFragmentResultListener(NativeRiskDialog.RESULT, viewLifecycleOwner) { _, result ->
            nativeConfirmed = result.getBoolean(NativeRiskDialog.ACCEPTED)
            if (!nativeConfirmed) restoreBackendSelection()
        }
        binding.backend.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, selectedView: View?, position: Int, id: Long) {
                if (types[position] != BackendType.NATIVE) nativeConfirmed = false
                else if (ScenarioRuntime.state.value.backend != BackendType.NATIVE && !nativeConfirmed) confirmNative()
            }
        }
        binding.applyBackend.setOnClickListener {
            val target = types[binding.backend.selectedItemPosition]
            if (target == BackendType.NATIVE && !nativeConfirmed &&
                ScenarioRuntime.state.value.backend != BackendType.NATIVE) {
                confirmNative()
                return@setOnClickListener
            }
            if (target == BackendType.NATIVE) {
                requireContext().getSharedPreferences("scenario_runtime", Context.MODE_PRIVATE)
                    .edit().putBoolean("native_opt_in", true).apply()
            }
            viewLifecycleOwner.lifecycleScope.launch {
                val selected = ScenarioRuntime.selectBackend(target).await()
                if (!selected) restoreBackendSelection()
                Toast.makeText(context, if (selected) "已切换模式" else "请先停止场景并完成资源清理", Toast.LENGTH_LONG).show()
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
        binding.rebuildProviders.setOnClickListener {
            binding.rebuildProviders.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                val rebuilt = ScenarioRuntime.rebuildMockProviders().await()
                binding.rebuildProviders.isEnabled = ScenarioRuntime.state.value.isActive &&
                    ScenarioRuntime.state.value.backend == BackendType.MOCK_PROVIDER
                Toast.makeText(context, if (rebuilt) "通道已重建，场景进度已保留" else "重建失败，请查看运行日志并检查授权", Toast.LENGTH_LONG).show()
            }
        }
        binding.openLocationProbe.setOnClickListener {
            startActivity(Intent(requireContext(), com.system.location.service.ui.diagnostics.LocationProbeActivity::class.java))
        }
        binding.refreshDiagnostics.setOnClickListener {
            ScenarioRuntime.refreshDiagnostics()
            refreshEnvironment(binding, view)
        }
        fun renderState(state: com.system.location.service.core.runtime.RuntimeState) {
            binding.phaseTitle.setTextIfChanged(state.phase.displayLabel())
            binding.phaseTitle.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(),
                when (state.phase) { RuntimePhase.RUNNING -> R.color.status_success; RuntimePhase.ERROR -> R.color.status_error; else -> R.color.text_primary }))
            binding.overview.setTextIfChanged("${backendLabel(state.backend)} · ${state.scenarioName ?: "未选择场景"}")
            renderEnvironment(binding, state)
            binding.routeProgress.visibility = if (state.routeId != null) View.VISIBLE else View.GONE
            val progress = (state.progress * 1000).toInt().coerceIn(0, 1000)
            if (binding.routeProgress.progress != progress) binding.routeProgress.progress = progress
            binding.applyBackend.isEnabled = !state.isActive
            binding.backend.isEnabled = !state.isActive
            binding.modeHint.setTextIfChanged(getString(if (state.isActive) R.string.runtime_mode_running else R.string.runtime_mode_hint))
            binding.rebuildProviders.isEnabled = state.isActive && state.backend == BackendType.MOCK_PROVIDER
            binding.stopRuntime.isEnabled = state.isActive || state.phase == RuntimePhase.ERROR
            if (binding.stateDetails.visibility == View.VISIBLE) binding.runtimeState.setTextIfChanged(buildString {
                val health = state.submissionHealth
                appendLine("提交 ${health.count} 次 · 最近间隔 ${health.lastGapMs.toLong()}ms · 最大间隔 ${health.maxGapMs.toLong()}ms")
                appendLine("最近耗时 ${health.lastLatencyMs.toLong()}ms · 最大耗时 ${health.maxLatencyMs.toLong()}ms · 延迟 ${health.delayedCount} 次")
                appendLine("提交成功不代表目标 App 已采用定位")
                appendLine("当前后端：${backendLabel(state.backend)}\n状态：${binding.phaseTitle.text}")
                appendLine("场景：${state.scenarioName ?: "未选择"}\n路线：${state.routeId ?: "—"}")
                state.sample?.let {
                    appendLine(String.format(Locale.ROOT, "WGS84：%.6f, %.6f", it.latitude, it.longitude))
                    appendLine(String.format(Locale.ROOT, "速度：%.2f km/h　进度：%.1f%%", it.speed * 3.6, state.progress * 100))
                }
                state.error?.let { appendLine("${it.stage}\n${it.reason}\n${it.suggestion}") }
            })
            binding.pauseResume.isEnabled = state.phase in setOf(RuntimePhase.RUNNING, RuntimePhase.PAUSED)
            val action = if (state.phase == RuntimePhase.PAUSED) "继续" else "暂停"
            if (binding.pauseResume.text != action) {
                binding.pauseResume.text = action
                binding.pauseResume.setIconResource(if (state.phase == RuntimePhase.PAUSED) R.drawable.baseline_play_24 else R.drawable.ic_pause)
            }
            if (binding.capabilityDetails.visibility == View.VISIBLE) binding.capabilities.setTextIfChanged(
                state.capabilities.entries.joinToString("\n\n") { (capability, status) ->
                    "${capabilityLabel(capability)}：${when (status.availability) {
                        Availability.AVAILABLE -> "可用"; Availability.REQUIRES_ACTION -> "需要设置"
                        Availability.UNAVAILABLE -> "不可用"; Availability.EXPERIMENTAL -> "实验性"
                    }}\n${status.reason}"
                })
        }
        renderRuntime = { renderState(ScenarioRuntime.state.value) }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                refreshEnvironment(binding, view)
                launch {
                    ScenarioRuntime.state.displayStates().collect { state ->
                        renderState(state)
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
    private fun refreshEnvironment(binding: FragmentRuntimeBinding, owner: View) {
        val app = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { RuntimeEnvironment.inspect(app) } }
            if (view !== owner) return@launch
            environment = result.getOrNull()
            environmentCheckFailed = result.isFailure
            renderEnvironment(binding, ScenarioRuntime.state.value)
        }
    }

    private var environmentRenderKey: String? = null
    private fun renderEnvironment(binding: FragmentRuntimeBinding, state: com.system.location.service.core.runtime.RuntimeState) {
        val env = environment
        val key = "$env/${state.backend}/${state.error}/$environmentCheckFailed"
        if (environmentRenderKey == key && binding.environmentRows.childCount > 0) return
        environmentRenderKey = key
        val ctx = binding.root.context
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        binding.environmentRows.removeAllViews()
        fun row(title: Int, value: Int, ready: Boolean?) {
            val line = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(10))
            }
            val color = androidx.core.content.ContextCompat.getColor(ctx,
                if (ready == true) R.color.status_success else R.color.text_secondary)
            val icon = android.widget.ImageView(ctx).apply {
                setImageResource(if (ready == true) R.drawable.ic_runtime_check else R.drawable.ic_runtime_circle)
                imageTintList = android.content.res.ColorStateList.valueOf(color)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            line.addView(icon, android.widget.LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(12) })
            val labels = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
            }
            labels.addView(android.widget.TextView(ctx).apply {
                text = getString(title)
                textSize = 14f
                setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.text_primary))
            })
            labels.addView(android.widget.TextView(ctx).apply {
                text = getString(value)
                textSize = 12f
                setPadding(0, dp(3), 0, 0)
                setTextColor(color)
            })
            line.addView(labels, android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            binding.environmentRows.addView(line)
        }
        if (env == null) {
            row(R.string.runtime_environment_title, if (environmentCheckFailed) R.string.runtime_check_failed else R.string.runtime_checking, null)
        } else {
            row(R.string.runtime_root, if (env.rootPresent) R.string.runtime_root_detected else R.string.runtime_root_absent, null)
            row(R.string.runtime_xposed, if (env.moduleConnected) R.string.runtime_connected else R.string.runtime_disconnected, env.moduleConnected)
            row(R.string.runtime_mock, if (env.mockAllowed) R.string.runtime_configured else R.string.runtime_not_configured, env.mockAllowed)
            row(R.string.runtime_permission, if (env.preciseLocation) R.string.runtime_granted else R.string.runtime_not_granted, env.preciseLocation)
            row(R.string.runtime_location, if (env.locationEnabled) R.string.runtime_enabled else R.string.runtime_disabled, env.locationEnabled)
        }
        val issues = buildList {
            state.error?.let { add(listOf(it.reason, it.suggestion).filter { text -> text.isNotBlank() }.joinToString("\n")) }
            env?.issues(state.backend)?.forEach { add(getString(it)) }
        }.distinct()
        binding.currentIssues.visibility = if (issues.isEmpty()) View.GONE else View.VISIBLE
        binding.currentIssues.setTextIfChanged(issues.joinToString("\n"))
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
