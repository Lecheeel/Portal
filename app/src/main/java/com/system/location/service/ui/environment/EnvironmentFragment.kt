package com.system.location.service.ui.environment

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.system.location.service.R
import com.system.location.service.core.environment.EnvironmentProfile
import com.system.location.service.core.environment.EnvironmentProfileCodec
import com.system.location.service.core.environment.WifiFingerprintImporter
import com.system.location.service.databinding.FragmentEnvironmentBinding
import com.system.location.service.environment.EnvironmentCollector
import com.system.location.service.environment.EnvironmentStore
import com.system.location.service.ext.cellMode
import com.system.location.service.ext.environmentApCount
import com.system.location.service.ext.environmentReplayJson
import com.system.location.service.ext.environmentSourceMode
import com.system.location.service.ext.wifiFingerprintCatalogJson
import com.system.location.service.ext.wifiFingerprintEnabled
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Environment capture, libraries and replay controls, opened from developer tools. */
class EnvironmentFragment : Fragment() {
    private var _binding: FragmentEnvironmentBinding? = null
    private val binding get() = _binding!!
    private var environmentExport: Pair<String, EnvironmentProfile>? = null

    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (_binding != null) {
            if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true) requestEnvironmentCapture()
            else showToast(getString(R.string.environment_capture_requires_location))
        }
    }

    private val bleScanPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (_binding != null) {
            if (!granted) showToast(getString(R.string.environment_ble_permission_denied))
            captureEnvironment()
        }
    }

    private val createEnvironmentDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val pending = environmentExport
        environmentExport = null
        if (uri == null || pending == null) return@registerForActivityResult
        runCatching {
            requireContext().contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(EnvironmentProfileCodec.encode(pending.second).toByteArray(Charsets.UTF_8))
            } ?: error("无法写入文件")
        }.onSuccess {
            if (isAdded) showToast(getString(R.string.environment_export_success))
        }.onFailure { error ->
            if (isAdded) showToast(getString(R.string.environment_export_failed, error.message ?: "unknown"))
        }
    }

    private val importEnvironmentDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val appContext = requireContext().applicationContext
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val raw = appContext.contentResolver.openInputStream(uri)
                        ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                        ?: error("无法读取文件")
                    val profile = EnvironmentProfileCodec.decode(raw)
                    val name = "import-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                    EnvironmentStore(appContext).save(name, profile)
                    name
                }
            }
            result.onSuccess { name ->
                if (isAdded) showToast(getString(R.string.environment_import_success, name))
            }.onFailure { error ->
                if (isAdded) showToast(getString(R.string.environment_import_failed, error.message ?: "unknown"))
            }
        }
    }

    private val importWifiFingerprintDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val appContext = requireContext().applicationContext
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val raw = appContext.contentResolver.openInputStream(uri)
                        ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                        ?: error("无法读取文件")
                    val imported = WifiFingerprintImporter.importJson(raw)
                    appContext.wifiFingerprintCatalogJson =
                        WifiFingerprintImporter.encodeJson(imported.catalog)
                    imported
                }
            }
            result.onSuccess { imported ->
                if (isAdded) showToast(getString(R.string.settings_wifi_imported, imported.imported))
            }.onFailure { error ->
                if (isAdded) showToast(getString(R.string.settings_wifi_import_failed, error.message ?: "unknown"))
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentEnvironmentBinding.inflate(inflater, container, false)
        val context = requireContext()
        binding.environmentListButton.setOnClickListener {
            val names = EnvironmentStore(context.applicationContext).list()
            if (names.isEmpty()) {
                showToast(getString(R.string.environment_list_empty))
                return@setOnClickListener
            }
            showEnvironmentList(names)
        }
        binding.environmentImportButton.setOnClickListener {
            importEnvironmentDocument.launch(arrayOf("application/json", "text/plain", "*/*"))
        }
        binding.wifiFingerprintSwitch.isChecked = context.wifiFingerprintEnabled
        binding.wifiFingerprintSwitch.setOnCheckedChangeListener { _, enabled ->
            context.wifiFingerprintEnabled = enabled
            applyConfigNowWithToast()
        }
        binding.wifiFingerprintImportButton.setOnClickListener {
            importWifiFingerprintDocument.launch(arrayOf("application/json", "text/plain", "*/*"))
        }
        fun apLabel() {
            binding.environmentApButton.text = getString(R.string.environment_ap_value, context.environmentApCount)
        }
        apLabel()
        binding.environmentApButton.setOnClickListener {
            showDialog(getString(R.string.environment_ap_title), context.environmentApCount.toString()) { value ->
                val count = value.toIntOrNull()
                if (count == null || count !in 1..8) {
                    showToast(getString(R.string.settings_environment_ap_range))
                    return@showDialog
                }
                context.environmentApCount = count
                apLabel()
                applyConfigNowWithToast()
            }
        }
        fun cellLabel() {
            binding.cellModeButton.text = getString(R.string.cell_mode_value, context.cellMode)
        }
        cellLabel()
        binding.cellModeButton.setOnClickListener {
            val modes = arrayOf("AUTO", "FULL_REPLAY", "NO_SIM_WIFI_ONLY", "LEGACY_CDMA")
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.cell_mode_title)
                .setSingleChoiceItems(modes, modes.indexOf(context.cellMode).coerceAtLeast(0)) { dialog, which ->
                    context.cellMode = modes[which]
                    cellLabel()
                    dialog.dismiss()
                    applyConfigNowWithToast()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        fun sourceLabel() {
            binding.environmentSourceButton.text =
                getString(R.string.environment_source_value, context.environmentSourceMode)
        }
        sourceLabel()
        binding.environmentSourceButton.setOnClickListener {
            val modes = arrayOf("AUTO", "PROCEDURAL", "REPLAY")
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.environment_source_title)
                .setSingleChoiceItems(modes, modes.indexOf(context.environmentSourceMode).coerceAtLeast(0)) { dialog, which ->
                    context.environmentSourceMode = modes[which]
                    sourceLabel()
                    dialog.dismiss()
                    applyConfigNowWithToast()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        binding.environmentCaptureButton.setOnClickListener { requestEnvironmentCapture() }
        return binding.root
    }

    private fun requestEnvironmentCapture() {
        val context = requireContext()
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            locationPermission.launch(arrayOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ))
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED
        ) {
            bleScanPermission.launch(Manifest.permission.BLUETOOTH_SCAN)
            return
        }
        captureEnvironment()
    }

    private fun captureEnvironment() {
        binding.environmentCaptureButton.isEnabled = false
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val profile = EnvironmentCollector(appContext)
                        .capture(origin = null, seed = System.currentTimeMillis())
                    val name = "capture-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                    EnvironmentStore(appContext).save(name, profile)
                    Triple(name, profile.wifi.size, profile.cells.size)
                }
            }
            if (_binding != null) binding.environmentCaptureButton.isEnabled = true
            result.onSuccess { (name, wifi, cells) ->
                if (isAdded) showToast(getString(R.string.environment_capture_saved, name, wifi, cells))
            }.onFailure { error ->
                if (isAdded) showToast(getString(R.string.environment_capture_failed, error.message ?: "unknown"))
            }
        }
    }
    private fun applyConfigNowWithToast() {
        lifecycleScope.launch {
            val applied = withContext(Dispatchers.IO) {
                com.system.location.service.runtime.ScenarioRuntime.applyConfigNow()
            }
            if (isAdded) showToast(if (applied) "配置已应用" else "已保存，下次启动场景生效")
        }
    }

    private fun showDialog(titleText: String, valueText: String, handler: (String) -> Unit) {
        val inflater = LayoutInflater.from(requireContext())
        val dialogView = inflater.inflate(R.layout.dialog_input, null)

        val title = dialogView.findViewById<TextView>(R.id.title)
        title.text = titleText

        val value = dialogView.findViewById<TextInputEditText>(R.id.value)
        value.setText(valueText)

        val builder = MaterialAlertDialogBuilder(requireContext())
        builder.setTitle(null)
        builder
            .setCancelable(false)
            .setView(dialogView)
            .setPositiveButton("保存") { _, _ ->
                handler(value.text.toString())
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showEnvironmentList(names: List<String>) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.environment_list_title)
            .setItems(names.toTypedArray()) { _, index -> showEnvironmentActions(names[index]) }
            .setNeutralButton(R.string.environment_search) { _, _ -> showEnvironmentSearch(names) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showEnvironmentSearch(all: List<String>) {
        val input = TextInputEditText(requireContext()).apply {
            hint = getString(R.string.environment_search_hint)
            setSingleLine()
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.environment_search)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val query = input.text?.toString()?.trim().orEmpty()
                if (query.isEmpty()) return@setPositiveButton
                val matches = all.filter { it.contains(query, ignoreCase = true) }
                if (matches.isEmpty()) showToast(getString(R.string.environment_no_match))
                else showEnvironmentList(matches)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showEnvironmentActions(name: String) {
        val appContext = requireContext().applicationContext
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(name)
            .setItems(arrayOf(
                getString(R.string.environment_export),
                getString(R.string.environment_replay),
                getString(R.string.environment_rename),
                getString(R.string.environment_delete),
            )) { _, action ->
                when (action) {
                    3 -> {
                        val deleted = EnvironmentStore(appContext).delete(name)
                        showToast(if (deleted) getString(R.string.environment_deleted)
                            else getString(R.string.environment_delete_failed))
                    }
                    2 -> {
                        val input = TextInputEditText(requireContext()).apply {
                            setText(name)
                            setSelection(name.length)
                        }
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle(R.string.environment_rename)
                            .setView(input)
                            .setPositiveButton(android.R.string.ok) { _, _ ->
                                runCatching {
                                    EnvironmentStore(appContext).rename(
                                        name, input.text?.toString().orEmpty().trim(),
                                    )
                                }.onSuccess {
                                    showToast(getString(R.string.environment_renamed))
                                }.onFailure { error ->
                                    showToast(getString(
                                        R.string.environment_rename_failed,
                                        error.message ?: "unknown",
                                    ))
                                }
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }
                    else -> runCatching { EnvironmentStore(appContext).load(name) }
                        .onSuccess { profile ->
                            if (action == 0) {
                                environmentExport = name to profile
                                createEnvironmentDocument.launch("$name.json")
                            } else {
                                requireContext().environmentReplayJson = EnvironmentProfileCodec.encode(profile)
                                lifecycleScope.launch {
                                    val applied = withContext(Dispatchers.IO) {
                                        com.system.location.service.runtime.ScenarioRuntime.applyConfigNow()
                                    }
                                    showToast(if (applied) "回放环境已应用" else getString(R.string.environment_replay_set))
                                }
                            }
                        }
                        .onFailure { error ->
                            showToast(getString(R.string.environment_export_failed, error.message ?: "unknown"))
                        }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showToast(message: String) {
        if (isAdded) Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
