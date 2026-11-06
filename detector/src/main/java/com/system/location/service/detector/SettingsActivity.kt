package com.system.location.service.detector

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.Toast
import android.widget.Toolbar

class SettingsActivity : Activity() {

    private lateinit var targetPackage: EditText
    private lateinit var sampleSeconds: EditText
    private lateinit var testGms: Switch
    private lateinit var checkNetworkIp: Switch
    private lateinit var readInstalledApps: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        toolbar.title = getString(R.string.settings_title)
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
        toolbar.setNavigationOnClickListener { finish() }

        targetPackage = findViewById(R.id.targetPackage)
        sampleSeconds = findViewById(R.id.sampleSeconds)
        testGms = findViewById(R.id.testGms)
        checkNetworkIp = findViewById(R.id.checkNetworkIp)
        readInstalledApps = findViewById(R.id.readInstalledApps)

        val settings = DetectorSettings.get(this)
        targetPackage.setText(settings.targetPackage)
        sampleSeconds.setText(settings.sampleSeconds.toString())
        testGms.isChecked = settings.testGms
        checkNetworkIp.isChecked = settings.checkNetworkIp
        readInstalledApps.isChecked = settings.readInstalledApps

        findViewById<Button>(R.id.saveButton).setOnClickListener { save(settings) }
    }

    private fun save(settings: DetectorSettings) {
        val packageName = targetPackage.text?.toString()?.trim().orEmpty()
        if (packageName.isEmpty()) {
            targetPackage.error = getString(R.string.settings_invalid_package)
            return
        }
        val seconds = sampleSeconds.text?.toString()?.toIntOrNull()
        if (seconds == null || seconds !in DetectorSettings.MIN_SAMPLE_SECONDS..DetectorSettings.MAX_SAMPLE_SECONDS) {
            sampleSeconds.error = getString(R.string.settings_invalid_sample)
            return
        }
        settings.targetPackage = packageName
        settings.sampleSeconds = seconds
        settings.testGms = testGms.isChecked
        settings.checkNetworkIp = checkNetworkIp.isChecked
        settings.readInstalledApps = readInstalledApps.isChecked
        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
        finish()
    }
}
