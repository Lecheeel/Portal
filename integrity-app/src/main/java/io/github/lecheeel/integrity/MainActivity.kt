package io.github.lecheeel.integrity

import android.Manifest
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.tabs.TabLayout
import io.github.lecheeel.integrity.core.*
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var content: LinearLayout
    private lateinit var startStop: MaterialButton
    private lateinit var export: MaterialButton
    private lateinit var footer: TextView
    private var labelInput: Spinner? = null
    private var intervalInput: Spinner? = null
    private var notesInput: EditText? = null
    private var currentTab = 0
    private var labelIndex = 0
    private var intervalIndex = 1
    private var notes = ""
    private var pendingExportName: String? = null
    private var exportBusy = false
    private var sessions = emptyList<SessionReport>()
    private val selected = linkedSetOf<String>()
    private val live = mutableMapOf<String, TextView>()
    private val labels = arrayOf("真实定位基线", "Portal · Mock", "Portal · Xposed", "Portal · Native", "参考项目", "其他 / 未知")
    private val intervals = longArrayOf(250, 1000, 3000)
    private val dark get() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val background get() = Color.parseColor(if (dark) "#111722" else "#F3F5F8")
    private val surface get() = Color.parseColor(if (dark) "#1C2533" else "#FFFFFF")
    private val ink get() = Color.parseColor(if (dark) "#EDF2FA" else "#17243B")
    private val muted get() = Color.parseColor(if (dark) "#A5B3C8" else "#627189")
    private val accent get() = Color.parseColor(if (dark) "#91BFFF" else "#246BCE")
    private val refresh = object : Runnable {
        override fun run() { refreshLive(); handler.postDelayed(this, 1000) }
    }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (CapturePermissions.location(this)) beginCapture() else toast("未获得定位权限，可以继续查看界面和已保存实验")
    }
    private val createReport = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val name = pendingExportName; pendingExportName = null; exportBusy = false
        if (name != null) scope.launch {
            val snapshot = java.io.File(noBackupFilesDir, name)
            try {
                if (uri != null) {
                    withContext(Dispatchers.IO) {
                        check(snapshot.isFile) { "导出快照已丢失，请重新选择报告" }
                        val output = contentResolver.openOutputStream(uri, "wt") ?: error("无法打开目标文件")
                        output.use { target -> snapshot.inputStream().use { it.copyTo(target) } }
                    }
                    toast("报告已导出；含精确坐标，请按需分享")
                }
            } catch (error: Exception) { showError("导出失败", error) }
            finally { withContext(Dispatchers.IO) { snapshot.delete() } }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentTab = savedInstanceState?.getInt("tab") ?: 0
        labelIndex = savedInstanceState?.getInt("label") ?: 0
        intervalIndex = savedInstanceState?.getInt("interval") ?: 1
        notes = savedInstanceState?.getString("notes").orEmpty()
        pendingExportName = savedInstanceState?.getString("pendingExport")
        exportBusy = pendingExportName != null
        selected.addAll(savedInstanceState?.getStringArrayList("selected").orEmpty())
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(this@MainActivity.background) }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        WindowInsetsControllerCompat(window, root).apply { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(22), dp(22), dp(10))
            addView(text("Location Integrity", 26, bold = true))
            addView(text("定位证据观测 · 离线实验室", 13, muted).apply { setPadding(0, dp(5), 0, 0) })
        })
        val tabs = TabLayout(this).apply { setBackgroundColor(this@MainActivity.background); setTabTextColors(muted, accent); setSelectedTabIndicatorColor(accent) }
        arrayOf("概览", "信号", "证据", "实验").forEach { tabs.addTab(tabs.newTab().setText(it)) }
        root.addView(tabs)
        val scroll = ScrollView(this).apply { isFillViewport = true }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(16)) }
        scroll.addView(content); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(16), dp(5), dp(16), 0) }
        startStop = button("开始观测") { if (CaptureStore.view.active) ObservationService.stop(this) else requestStart() }
        export = button("导出报告") { CaptureStore.view.report?.let { requestExport(it) } ?: toast("请先开始观测或打开已保存实验") }
        controls.addView(startStop, LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginEnd = dp(8) })
        controls.addView(export, LinearLayout.LayoutParams(0, dp(54), 1f)); root.addView(controls)
        footer = text("无 Root · 无 Xposed · 无网络上传", 11, muted).apply { gravity = Gravity.CENTER; setPadding(dp(16), dp(7), dp(16), dp(12)) }
        root.addView(footer)
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) { rememberInputs(); currentTab = tab.position; buildPage() }
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        tabs.getTabAt(currentTab)?.select(); buildPage()
    }
    override fun onStart() { super.onStart(); handler.post(refresh); if (currentTab == 3) loadSessions() }
    override fun onStop() { handler.removeCallbacks(refresh); super.onStop() }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        rememberInputs(); outState.putInt("tab", currentTab); outState.putInt("label", labelIndex)
        outState.putInt("interval", intervalIndex); outState.putString("notes", notes); outState.putStringArrayList("selected", ArrayList(selected))
        outState.putString("pendingExport", pendingExportName)
        super.onSaveInstanceState(outState)
    }
    private fun rememberInputs() {
        labelInput?.let { labelIndex = it.selectedItemPosition.coerceAtLeast(0) }; intervalInput?.let { intervalIndex = it.selectedItemPosition.coerceAtLeast(0) }
        notesInput?.let { notes = it.text.toString() }
    }
    private fun buildPage() {
        live.clear(); labelInput = null; intervalInput = null; notesInput = null; content.removeAllViews()
        when (currentTab) { 0 -> overview(); 1 -> signals(); 2 -> evidence(); else -> { textBlock("保存与对比", "最多保存 20 次实验。每 15 秒保存快照；进程意外退出后，最后快照标记为中断。报告包含坐标，导出前请确认分享范围。"); loadSessions() } }
        refreshLive()
    }
    private fun overview() {
        card("观测结论") { dynamic("verdict", 21, true); dynamic("verdictNote", 13) }
        card("本次采集") { dynamic("metrics", 16); dynamic("health", 12) }
        card("实验设置") {
            addView(text("测试标签 · 手动注释，不参与检测", 12, muted))
            labelInput = spinner(labels, labelIndex); addView(labelInput)
            addView(text("请求采样间隔 · 系统可能合并或延迟回调", 12, muted))
            intervalInput = spinner(arrayOf("250 ms · 高频实验", "1000 ms · 默认", "3000 ms · 低频观测"), intervalIndex); addView(intervalInput)
            notesInput = EditText(this@MainActivity).apply {
                hint = "项目版本、后端设置、室内/室外等备注"; setText(notes); setTextColor(ink); setHintTextColor(muted)
                textSize = 14f; minLines = 2; maxLines = 4; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                filters = arrayOf(android.text.InputFilter.LengthFilter(2000))
            }
            addView(notesInput)
        }
        card("权限与可见范围") {
            dynamic("permissions", 13)
            addView(button("打开权限与应用设置") { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + packageName))) })
        }
        textBlock("读数不是证明", "未观察到模拟标记，不等于定位绝对真实。无卫星、无传感器数据、设备有 Root 均不单独判定模拟。另一个 App 被定向修改时，本应用的读数可能不同。")
    }
    private fun signals() {
        card("定位来源 · WGS84") { dynamic("sources", 13) }
        card("GNSS · 卫星与原始测量") { dynamic("gnss", 13) }
        card("NMEA · 最近 4 条") { dynamic("nmea", 11) }
        card("运动传感器 · 最新快照") { dynamic("sensors", 13) }
        textBlock("采集边界", "GNSS 原始测量的可用性依设备而异；缺失不判假。系统 fused 来源不等于 Google Play 服务的 FusedLocationProviderClient，本版不包含 GMS SDK 或地图 SDK。")
    }
    private fun evidence() {
        card("可解释证据") { dynamic("findings", 14) }
        card("事件时间线") {
            addView(button("添加实验标记") {
                if (!CaptureStore.view.active) toast("先开始一次观测") else {
                    val input = EditText(this@MainActivity).apply { hint = "例如：切换后端、暂停、关闭模拟" }
                    AlertDialog.Builder(this@MainActivity).setTitle("添加时间线标记").setView(input)
                        .setPositiveButton("记录") { _, _ -> ObservationService.mark(this@MainActivity, input.text.toString()) }.setNegativeButton("取消", null).show()
                }
            }); dynamic("timeline", 12)
        }
        textBlock("规则说明", "直接标记：本应用观察到 isMock=true。\n异常提示：样本超过 10 秒、单调时间倒退或超前、超精度容差的位置跳变、同期来源差异。\n重复坐标仅作信息展示。近似位置权限下关闭位移和来源差异推断。所有阈值均为实验启发式规则，未经过真实设备数据集校准。")
    }
    private fun loadSessions() {
        scope.launch {
            try {
                sessions = withContext(Dispatchers.IO) { ReportRepository(this@MainActivity).list() }
                selected.retainAll(sessions.map { it.id }.toSet())
                if (currentTab == 3) renderSessions()
            } catch (error: Exception) { if (currentTab == 3) textBlock("读取失败", error.message ?: "实验数据无法读取"); showError("实验记录读取失败", error) }
        }
    }
    private fun renderSessions() {
        content.removeAllViews(); live.clear()
        textBlock("实验记录", "选择两次实验进行对比；手动标签只是测试注释。最多保存 20 次，每次最多保留最新 2000 个定位样本、80 条 NMEA 和 300 个事件。")
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(button("对比两次") { compareSelected() }, LinearLayout.LayoutParams(0, dp(52), 1f))
        actions.addView(button("刷新") { loadSessions() }, LinearLayout.LayoutParams(0, dp(52), 1f)); content.addView(actions)
        if (sessions.isEmpty()) textBlock("还没有实验", "开始观测，切换到 Portal 或参考项目操作，再回到这里停止并保存。无需将本应用设为模拟位置应用。")
        sessions.forEach { report -> card(report.label.ifBlank { "未命名实验" }) {
            addView(text(date(report.startedAtMillis) + " · " + report.status + "\n" + verdictLabel(report.verdict) + " · " + report.health.totalSamples + " 个样本", 13, muted))
            val check = CheckBox(this@MainActivity).apply { text = "加入对比"; setTextColor(ink); isChecked = report.id in selected
                setOnCheckedChangeListener { _, checked ->
                    if (checked && selected.size >= 2 && report.id !in selected) { isChecked = false; toast("最多选择两次实验") }
                    else if (checked) selected += report.id else selected -= report.id
                }
            }; addView(check)
            addView(button("查看 / 导出") { AlertDialog.Builder(this@MainActivity).setTitle(report.label).setMessage(reportDescription(report))
                .setPositiveButton("导出 JSON") { _, _ -> requestExport(report) }.setNegativeButton("关闭", null).show() })
        } }
    }
    private fun compareSelected() {
        val chosen = sessions.filter { it.id in selected }
        if (chosen.size != 2) { toast("请选择两次实验"); return }
        val a = chosen[0]; val b = chosen[1]
        val difference = "A · " + reportDescription(a) + "\n\nB · " + reportDescription(b) +
            "\n\n仅 A 出现的证据：" + (a.evidence.map { it.code }.toSet() - b.evidence.map { it.code }.toSet()).joinToString().ifEmpty { "无" } +
            "\n仅 B 出现的证据：" + (b.evidence.map { it.code }.toSet() - a.evidence.map { it.code }.toSet()).joinToString().ifEmpty { "无" } +
            "\n\n请尽量保持设备、权限、室内外条件与时长一致。统计差异不等于某方案更真实。"
        AlertDialog.Builder(this).setTitle("实验对比 · A / B").setMessage(difference).setPositiveButton("导出 A") { _, _ -> requestExport(a) }
            .setNeutralButton("导出 B") { _, _ -> requestExport(b) }.setNegativeButton("关闭", null).show()
    }
    private fun reportDescription(r: SessionReport): String = r.label + "\n" + date(r.startedAtMillis) + " · " + r.status +
        "\n设备：" + r.device["model"] + " / API " + r.device["sdk"] + "\n精确权限：" + r.permissions["fineLocation"] +
        "\n请求间隔：" + r.requestedIntervalMillis + " ms；时长：" + duration(r) + "\n结论：" + verdictLabel(r.verdict) +
        "\n样本：" + r.health.totalSamples + "（实时 " + r.health.liveSamples + "，保留 " + r.health.retainedSamples + "）" +
        "\n卫星/原始测量/NMEA 回调：" + r.gnss.statusCallbacks + "/" + r.gnss.rawCallbacks + "/" + r.gnss.nmeaCallbacks +
        "\n" + r.sources.joinToString("\n") { it.provider + " · Mock=" + it.mockSamples + " · 回调P95=" + number(it.intervalP95Millis, 0) + "ms" } +
        "\n证据：" + r.evidence.joinToString { it.code }.ifEmpty { "尚无" } + "\n备注：" + r.notes.ifEmpty { "无" }
    private fun refreshLive() {
        if (!::startStop.isInitialized) return
        val view = CaptureStore.view; val r = view.report
        change(startStop, if (view.active) "停止并保存" else if (view.saving) "正在保存…" else "开始观测")
        startStop.isEnabled = !view.saving; export.isEnabled = r != null && !exportBusy
        labelInput?.isEnabled = !view.active && !view.saving; intervalInput?.isEnabled = !view.active && !view.saving; notesInput?.isEnabled = !view.active && !view.saving
        change(footer, view.storageError ?: if (view.active || r != null) view.message else "无 Root · 无 Xposed · 无网络上传")
        footer.setTextColor(if (view.storageError != null) Color.parseColor("#E56565") else muted)
        put("verdict", r?.let { verdictLabel(it.verdict) } ?: "等待开始观测")
        live["verdict"]?.setTextColor(when (r?.verdict) { "MOCK_OBSERVED" -> Color.parseColor(if (dark) "#FF9292" else "#B82E43"); "ANOMALIES_OBSERVED" -> Color.parseColor(if (dark) "#F5CE87" else "#986000"); else -> accent })
        put("verdictNote", if (r == null) "开始后展示本应用实际收到的定位证据。" else "本次会话的累计观测；并非对整台手机或其他 App 的真实性证明。")
        put("metrics", if (r == null) "定位样本  —       实时回调  —\n采集时长  —       模拟标记  —" else
            "定位样本  " + r.health.totalSamples + "       实时回调  " + r.health.liveSamples + "\n采集时长  " + duration(r) + "       模拟标记  " + r.sources.sumOf { it.mockSamples })
        put("health", r?.let { "本进程 PSS " + number(it.health.processPssKb?.div(1024.0), 1) + " MB · CPU " + number(it.health.processCpuOneCorePercent, 1) + "%（单核口径）\n实际回调由系统决定；不同来源可能重复报告同一位置。" } ?: "性能指标在开始后采集；不估算电池消耗。")
        put("permissions", "精确位置：" + ifGranted(CapturePermissions.has(this, Manifest.permission.ACCESS_FINE_LOCATION)) +
            "\n近似位置：" + ifGranted(CapturePermissions.has(this, Manifest.permission.ACCESS_COARSE_LOCATION)) +
            "\nAndroid " + Build.VERSION.RELEASE + " · API " + Build.VERSION.SDK_INT + "\n仅近似权限时仍可采集，但不执行位移/来源差异推断。无需选择模拟位置应用。")
        put("sources", if (r == null) "尚未开始订阅定位来源。" else r.providerStates.entries.joinToString("\n\n") { (name, state) ->
            val sample = r.samples.lastOrNull { it.source == name }; val stats = r.sources.firstOrNull { it.provider == name }
            name.uppercase(Locale.ROOT) + " · " + state + if (sample == null) "\n尚无样本" else
                "\n" + number(sample.latitude, 7) + ", " + number(sample.longitude, 7) + "\n精度 " + number(sample.accuracyMeters, 1) +
                "m · 速度 " + number(sample.speedMps, 2) + "m/s · 方位 " + number(sample.bearingDegrees, 1) + "°" +
                "\nMock=" + sample.isMock + " · " + (if (sample.cached) "缓存" else "实时回调") + " · 接收时年龄 " + number(sample.ageMillis, 0) + "ms" +
                "\n回调间隔 P50/P95 " + number(stats?.intervalP50Millis, 0) + "/" + number(stats?.intervalP95Millis, 0) + "ms"
        })
        val g = r?.gnss
        put("gnss", if (g == null) "等待观测；GNSS 需要精确位置权限。" else
            g.statuses.entries.joinToString("\n") { it.key + "：" + it.value } + "\n\n卫星 " + g.satellites.size + " · 用于定位 " + g.satellites.count { it.usedInFix } +
            "\n状态/原始回调 " + g.statusCallbacks + "/" + g.rawCallbacks + " · 最新测量 " + g.rawMeasurements.size +
            "\n卫星状态距快照 " + age(r.capturedElapsedNanos, g.lastStatusElapsedNanos) + " · 原始测量 " + age(r.capturedElapsedNanos, g.lastRawElapsedNanos) +
            "\n" + g.satellites.take(8).joinToString("\n") { "星座 " + it.constellation + " / SVID " + it.svid + " · C/N₀ " + number(it.cn0DbHz, 1) + " dB-Hz" + if (it.usedInFix) " · USED" else "" } +
            "\n\n原始时钟 timeNanos=" + (g.clock?.timeNanos ?: "—") + "\nfullBiasNanos=" + (g.clock?.fullBiasNanos ?: "—") +
            "\n原始测量为最新回调快照；完整字段请导出 JSON。")
        put("nmea", if (g == null || g.nmea.isEmpty()) "未取得 NMEA；不据此判断模拟。" else g.nmea.takeLast(4).joinToString("\n\n"))
        val s = r?.sensors
        put("sensors", if (s == null) "等待观测；不申请运动识别或高频传感器权限。" else
            "加速度计：" + (if (s.accelerometerAvailable) "已订阅" else "不可用") + " · 回调 " + s.accelerometerEvents +
            "\nXYZ " + s.acceleration.joinToString { number(it, 3) }.ifEmpty { "—" } + " m/s²\n距快照 " + age(r.capturedElapsedNanos, s.lastAccelerationElapsedNanos) +
            "\n\n陀螺仪：" + (if (s.gyroscopeAvailable) "已订阅" else "不可用") + " · 回调 " + s.gyroscopeEvents +
            "\nXYZ " + s.gyroscope.joinToString { number(it, 3) }.ifEmpty { "—" } + " rad/s\n距快照 " + age(r.capturedElapsedNanos, s.lastGyroscopeElapsedNanos) +
            "\n\n仅展示数据，不用静止传感器直接判定坐标虚假。")
        put("findings", if (r == null || r.evidence.isEmpty()) "尚未记录到规则证据。未发现异常不代表真实定位。" else r.evidence.joinToString("\n\n") {
            (when (it.level) { "DIRECT" -> "[直接标记] "; "CAUTION" -> "[异常提示] "; else -> "[信息] " }) + it.title + " · " + it.provider + "\n" + it.detail
        })
        put("timeline", r?.timeline?.takeLast(35)?.asReversed()?.joinToString("\n\n") { time(it.atMillis) + "  " + it.type + "\n" + it.message } ?: "开始、结束、首次证据及手动标记将出现在这里。")
    }
    private fun requestStart() {
        rememberInputs()
        if (CapturePermissions.location(this)) beginCapture() else {
            val required = mutableListOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) required += Manifest.permission.POST_NOTIFICATIONS
            permissions.launch(required.toTypedArray())
        }
    }
    private fun beginCapture() {
        try { ContextCompat.startForegroundService(this, Intent(this, ObservationService::class.java).setAction(ObservationService.START)
            .putExtra("label", labels[labelIndex.coerceIn(labels.indices)]).putExtra("notes", notes.take(2000)).putExtra("interval", intervals[intervalIndex.coerceIn(intervals.indices)])) }
        catch (error: Exception) { showError("无法开始观测", error) }
    }
    private fun requestExport(report: SessionReport) {
        if (exportBusy) return
        exportBusy = true
        scope.launch {
            try {
                val name = "pending-export-" + report.id + ".json"
                withContext(Dispatchers.IO) { java.io.File(noBackupFilesDir, name).writeText(ReportRepository.encode(report)) }
                pendingExportName = name
                createReport.launch("LocationIntegrity-" + report.id.take(8) + ".json")
            } catch (error: Exception) { exportBusy = false; showError("无法准备导出", error) }
        }
    }
    private fun verdictLabel(value: String) = when (value) { "MOCK_OBSERVED" -> "观察到模拟标记"; "ANOMALIES_OBSERVED" -> "存在需要解释的异常"; "NO_EXPLICIT_EVIDENCE" -> "未观察到明确模拟证据"; else -> "数据不足，暂不能判断" }
    private fun duration(r: SessionReport): String { val seconds = ((r.capturedElapsedNanos - r.startedElapsedNanos) / 1_000_000_000).coerceAtLeast(0); return "%02d:%02d".format(Locale.ROOT, seconds / 60, seconds % 60) }
    private fun age(now: Long, timestamp: Long?) = timestamp?.let { number((now - it) / 1_000_000_000.0, 1) + "s" } ?: "尚无回调"
    private fun number(value: Number?, decimals: Int): String = value?.toDouble()?.takeIf { it.isFinite() }?.let { String.format(Locale.ROOT, "%." + decimals + "f", it) } ?: "—"
    private fun date(value: Long) = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(value))
    private fun time(value: Long) = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(value))
    private fun ifGranted(value: Boolean) = if (value) "已授予" else "未授予"
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()
    private fun showError(title: String, error: Exception) { if (!isFinishing) AlertDialog.Builder(this).setTitle(title).setMessage(error.message ?: error.javaClass.simpleName).setPositiveButton("知道了", null).show() }
    private fun put(key: String, value: String) { live[key]?.let { change(it, value) } }
    private fun change(view: TextView, value: String) { if (view.text.toString() != value) view.text = value }
    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
    private fun text(value: String, size: Int, color: Int = ink, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color); setLineSpacing(dp(3).toFloat(), 1.08f)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }
    private fun button(value: String, click: () -> Unit) = MaterialButton(this).apply { text = value; isAllCaps = false; textSize = 14f; cornerRadius = dp(12); setOnClickListener { click() } }
    private fun spinner(values: Array<String>, index: Int) = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, values); setSelection(index.coerceIn(values.indices)); minimumHeight = dp(48)
    }
    private fun card(title: String, body: LinearLayout.() -> Unit) {
        val card = MaterialCardView(this).apply { radius = dp(18).toFloat(); cardElevation = 0f; setCardBackgroundColor(surface); strokeWidth = dp(1); strokeColor = Color.parseColor(if (dark) "#2B3749" else "#E1E7F0") }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(17), dp(18), dp(17)) }
        box.addView(text(title, 13, muted, true).apply { setPadding(0, 0, 0, dp(10)) }); box.body(); card.addView(box)
        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    }
    private fun LinearLayout.dynamic(key: String, size: Int, bold: Boolean = false) {
        val view = text("", size, ink, bold).apply { setTextIsSelectable(true); setPadding(0, dp(3), 0, dp(5)) }; live[key] = view; addView(view)
    }
    private fun textBlock(title: String, body: String) { card(title) { addView(text(body, 13, muted)) } }
}
