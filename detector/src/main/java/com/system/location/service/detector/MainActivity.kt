package com.system.location.service.detector

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.widget.Toolbar
import com.system.location.service.detector.model.CheckResult
import com.system.location.service.detector.model.CheckStatus
import com.system.location.service.detector.model.DetectorReport
import com.system.location.service.detector.model.DeviceInfoCollector
import com.system.location.service.detector.model.GnssReference
import java.util.concurrent.Executors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One screen: run, inspect and export.
 *
 * The detector never uploads a report; JSON leaves the device only when the user exports or copies
 * it. Checks are blocking Binder/API reads, so the whole run happens on one worker thread.
 */
class MainActivity : Activity() {

    private lateinit var toolbar: Toolbar
    private lateinit var summaryView: TextView
    private lateinit var deviceInfoView: TextView
    private lateinit var runButton: Button
    private lateinit var exportButton: Button
    private lateinit var copyButton: Button
    private lateinit var progress: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var resultsContainer: LinearLayout
    private lateinit var settings: DetectorSettings
    private var lastReportJson: String? = null
    private var lastReport: DetectorReport? = null
    private lateinit var saveReferenceButton: Button
    private lateinit var clearReferenceButton: Button
    private lateinit var referenceStatus: TextView
    private val referenceWorker = Executors.newSingleThreadExecutor()
    private var referenceBusy = false

    @Volatile private var running = false
    @Volatile private var destroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        toolbar = findViewById(R.id.toolbar)
        summaryView = findViewById(R.id.summary)
        deviceInfoView = findViewById(R.id.deviceInfo)
        runButton = findViewById(R.id.runButton)
        exportButton = findViewById(R.id.exportButton)
        copyButton = findViewById(R.id.copyButton)
        progress = findViewById(R.id.progress)
        progressText = findViewById(R.id.progressText)
        resultsContainer = findViewById(R.id.resultsContainer)
        settings = DetectorSettings.get(this)
        saveReferenceButton = findViewById(R.id.saveReferenceButton)
        clearReferenceButton = findViewById(R.id.clearReferenceButton)
        referenceStatus = findViewById(R.id.referenceStatus)
        saveReferenceButton.setOnClickListener { chooseReference() }
        clearReferenceButton.setOnClickListener {
            referenceTask { GnssReferenceStore.clear(applicationContext); getString(R.string.reference_cleared) }
        }

        toolbar.title = getString(R.string.app_name)
        runButton.setOnClickListener { runChecks() }
        exportButton.setOnClickListener { exportJson() }
        copyButton.setOnClickListener { copyJson() }
        findViewById<Button>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        deviceInfoView.text = deviceLine()
        referenceTask { null }
        ensurePermissions()
    }

    override fun onResume() {
        super.onResume()
        settings = DetectorSettings.get(this)
        deviceInfoView.text = deviceLine()
    }

    override fun onDestroy() {
        destroyed = true
        referenceWorker.shutdown()
        super.onDestroy()
    }

    private fun runChecks() {
        if (running || referenceBusy) return
        running = true
        val startedAt = System.currentTimeMillis()
        val runSettings = settings
        val appContext = applicationContext

        runButton.isEnabled = false
        exportButton.isEnabled = false
        copyButton.isEnabled = false
        updateReferenceButtons()
        progress.visibility = View.VISIBLE
        progress.progress = 0
        progressText.visibility = View.VISIBLE
        progressText.text = getString(R.string.progress_preparing)
        resultsContainer.removeAllViews()

        Thread({
            val report = DetectorRunner.run(appContext, runSettings)
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                if (report != null) {
                    finishRun(report, startedAt)
                } else {
                    running = false
                    updateReferenceButtons()
                    runButton.isEnabled = true
                    exportButton.isEnabled = lastReportJson != null
                    copyButton.isEnabled = lastReportJson != null
                    progress.visibility = View.GONE
                    progressText.visibility = View.GONE
                }
            }
        }, "detector-run").start()
    }

    private fun finishRun(report: DetectorReport, startedAt: Long) {
        lastReportJson = DetectorRunner.lastReportJson
        lastReport = report
        render(report)
        progress.visibility = View.GONE
        progressText.text = getString(
            R.string.progress_done,
            ((System.currentTimeMillis() - startedAt) / 1_000L).toInt(),
        )
        runButton.isEnabled = true
        exportButton.isEnabled = true
        copyButton.isEnabled = true
        running = false
        updateReferenceButtons()
    }

    private fun updateReferenceButtons() {
        saveReferenceButton.isEnabled = !running && !referenceBusy && lastReport?.gnssEvidence != null
        clearReferenceButton.isEnabled = !running && !referenceBusy
    }

    private fun chooseReference() {
        val report = lastReport ?: return
        val evidence = report.gnssEvidence ?: return
        val environment = EditText(this).apply {
            hint = getString(R.string.reference_environment)
            isSingleLine = true
            filters = arrayOf(android.text.InputFilter.LengthFilter(120))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.reference_save)
            .setMessage(R.string.reference_confirm)
            .setView(environment)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.reference_save, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val label = environment.text.toString().trim()
                if (label.isEmpty()) {
                    environment.error = getString(R.string.reference_environment)
                    return@setOnClickListener
                }
                val reference = GnssReference(Build.FINGERPRINT, label, System.currentTimeMillis(), evidence)
                dialog.dismiss()
                referenceTask {
                    GnssReferenceStore.save(applicationContext, reference)
                    getString(R.string.reference_saved)
                }
            }
        }
        dialog.show()
    }

    /** File reads, validation and atomic writes all run away from the UI thread. */
    private fun referenceTask(action: () -> String?) {
        if (referenceBusy || destroyed) return
        referenceBusy = true
        runButton.isEnabled = false
        updateReferenceButtons()
        referenceWorker.execute {
            val outcome = runCatching {
                val message = action()
                val loaded = GnssReferenceStore.load(applicationContext)
                val reference = loaded.reference
                val status = if (reference == null) loaded.note else {
                    val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(reference.savedAtMillis))
                    getString(R.string.reference_status, time, reference.environment)
                }
                status to message
            }
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                referenceBusy = false
                runButton.isEnabled = !running
                updateReferenceButtons()
                outcome.onSuccess { (status, message) ->
                    referenceStatus.text = status
                    if (message != null) Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }.onFailure {
                    Toast.makeText(this, getString(R.string.reference_failed,
                        it.message ?: it.javaClass.simpleName), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun render(report: DetectorReport) {
        summaryView.text = getString(R.string.summary_counts, report.passCount, report.failCount, report.unknownCount)
        resultsContainer.removeAllViews()
        listOf(CheckStatus.FAIL, CheckStatus.UNKNOWN, CheckStatus.PASS).forEach { status ->
            val items = report.checks.filter { it.status == status }
            if (items.isEmpty()) return@forEach
            resultsContainer.addView(sectionHeader(status, items.size))
            items.forEach { result -> resultsContainer.addView(resultView(result)) }
        }
    }

    private fun sectionHeader(status: CheckStatus, count: Int): TextView = TextView(this).apply {
        text = "${status.name} · $count"
        setPadding(0, 16, 0, 8)
        setTextColor(statusColor(status))
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
    }

    private fun resultView(result: CheckResult): View {
        val item = layoutInflater.inflate(R.layout.item_check, resultsContainer, false)
        item.findViewById<TextView>(R.id.checkId).text = result.id
        item.findViewById<TextView>(R.id.status).apply {
            text = result.status.name
            setTextColor(statusColor(result.status))
        }
        item.findViewById<TextView>(R.id.title).text =
            if (result.blocking) "${result.title} · 阻断级" else result.title
        item.findViewById<TextView>(R.id.detail).text = result.detail
        val evidenceView = item.findViewById<TextView>(R.id.evidence)
        val hint = item.findViewById<TextView>(R.id.hint)
        val evidence = result.evidence.joinToString("\n").trim()
        evidenceView.text = evidence
        evidenceView.visibility = View.GONE
        hint.visibility = if (evidence.isEmpty()) View.GONE else View.VISIBLE
        item.setOnClickListener {
            if (evidence.isEmpty()) return@setOnClickListener
            val showing = evidenceView.visibility == View.VISIBLE
            evidenceView.visibility = if (showing) View.GONE else View.VISIBLE
            hint.text = if (showing) getString(R.string.detail_hint) else ""
        }
        return item
    }

    private fun statusColor(status: CheckStatus): Int = getColor(
        when (status) {
            CheckStatus.PASS -> R.color.detector_pass
            CheckStatus.FAIL -> R.color.detector_fail
            CheckStatus.UNKNOWN -> R.color.detector_unknown
        },
    )

    private fun exportJson() {
        val json = lastReportJson
        if (json == null) {
            Toast.makeText(this, R.string.export_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "detector-$stamp.json")
        }
        runCatching { startActivityForResult(intent, REQUEST_EXPORT) }.onFailure { error ->
            Toast.makeText(
                this,
                getString(R.string.export_failed, error.message ?: error.javaClass.simpleName),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    @Deprecated("Platform document picker result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_EXPORT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val json = lastReportJson ?: return
        runCatching {
            contentResolver.openOutputStream(uri)?.use { stream -> stream.write(json.toByteArray(Charsets.UTF_8)) }
                ?: error("无法打开目标文件")
        }.onSuccess {
            Toast.makeText(this, getString(R.string.export_saved, uri.toString()), Toast.LENGTH_LONG).show()
        }.onFailure { error ->
            Toast.makeText(
                this,
                getString(R.string.export_failed, error.message ?: error.javaClass.simpleName),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun copyJson() {
        val json = lastReportJson
        if (json == null) {
            Toast.makeText(this, R.string.export_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("Portal Detector", json))
        Toast.makeText(this, R.string.copy_done, Toast.LENGTH_SHORT).show()
    }

    private fun deviceLine(): String {
        val device = DeviceInfoCollector.collect(this)
        return "${device.manufacturer} ${device.model} · Android ${device.android} (SDK ${device.sdk}) · " +
            (device.abis.firstOrNull() ?: "?")
    }

    private fun ensurePermissions() {
        val required = buildList {
            add(android.Manifest.permission.ACCESS_FINE_LOCATION)
            add(android.Manifest.permission.ACCESS_COARSE_LOCATION)
            add(android.Manifest.permission.READ_PHONE_STATE)
            add(android.Manifest.permission.ACTIVITY_RECOGNITION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(android.Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }
        val missing = required.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS && grantResults.any { it != PackageManager.PERMISSION_GRANTED }) {
            Toast.makeText(this, R.string.permission_needed, Toast.LENGTH_LONG).show()
        }
    }

    private companion object {
        const val REQUEST_PERMISSIONS = 1001
        const val REQUEST_EXPORT = 1002
    }
}
