package com.system.location.service.ui.mock

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.system.location.service.R
import com.system.location.service.core.runtime.RuntimePhase
import com.system.location.service.core.scenario.RouteMode
import com.system.location.service.databinding.FragmentRouteMockBinding
import com.system.location.service.runtime.ScenarioRuntime
import com.system.location.service.ui.viewmodel.*
import kotlinx.coroutines.launch

class RouteMockFragment : Fragment(R.layout.fragment_route_mock) {
    private val model by viewModels<LibraryViewModel>()
    private var importKind = LibraryKind.ROUTES
    private var exportKind = LibraryKind.ROUTES
    private val importFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.import(uri, importKind)
    }
    private val exportFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) model.export(uri, exportKind)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        importKind = runCatching { LibraryKind.valueOf(savedInstanceState?.getString("importKind") ?: "ROUTES") }.getOrDefault(LibraryKind.ROUTES)
        exportKind = runCatching { LibraryKind.valueOf(savedInstanceState?.getString("exportKind") ?: "ROUTES") }.getOrDefault(LibraryKind.ROUTES)
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val ui = FragmentRouteMockBinding.bind(view)
        ui.libraryKind.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, listOf("路线库", "场景库", "收藏位置"))
        ui.libraryKind.setSelection(model.state.value.kind.ordinal)
        ui.libraryKind.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { model.kind(LibraryKind.entries[position]) }
        }
        ui.addRoute.setOnClickListener { findNavController().navigate(R.id.nav_route_edit) }
        ui.importLibrary.setOnClickListener { importKind = model.state.value.kind; importFile.launch(arrayOf("application/json", "text/*")) }
        ui.exportLibrary.setOnClickListener { exportKind = model.state.value.kind; exportFile.launch("location-${exportKind.name.lowercase()}.json") }
        ui.pauseResume.setOnClickListener { if (ScenarioRuntime.state.value.phase == RuntimePhase.PAUSED) ScenarioRuntime.resume() else ScenarioRuntime.pause() }
        ui.stopScene.setOnClickListener { ScenarioRuntime.stop() }
        ui.libraryItems.setOnItemClickListener { _, _, position, _ -> model.state.value.items.getOrNull(position)?.let(::actions) }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { model.state.collect { state ->
                    ui.libraryStatus.text = state.message ?: if (state.busy) "正在读取/保存…" else "${state.items.size} 条记录"
                    ui.libraryItems.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, state.items.map { "${it.title}\n${it.detail}" })
                    ui.libraryItems.isEnabled = !state.busy
                    ui.libraryKind.isEnabled = !state.busy
                } }
                launch { ScenarioRuntime.state.collect { state ->
                    ui.runtimeStatus.text = "${state.backend} · ${state.phase} · ${state.scenarioName.orEmpty()}" +
                        (state.error?.let { "\n${it.stage}: ${it.reason}\n${it.suggestion}" } ?: "")
                    ui.pauseResume.isEnabled = state.phase in setOf(RuntimePhase.RUNNING, RuntimePhase.PAUSED)
                    ui.pauseResume.text = if (state.phase == RuntimePhase.PAUSED) "恢复" else "暂停"
                } }
            }
        }
    }
    private fun actions(item: LibraryItem) {
        val choices = mutableListOf("启动", "重命名", "复制", "删除")
        if (model.state.value.kind != LibraryKind.SCENARIOS) choices += "收藏 / 取消收藏"
        if (model.state.value.kind != LibraryKind.LOCATIONS) choices += "播放模式"
        MaterialAlertDialogBuilder(requireContext()).setTitle(item.title).setItems(choices.toTypedArray()) { _, which ->
            when (choices[which]) {
                "启动" -> model.start(item.id)
                "重命名", "复制" -> {
                    val name = EditText(requireContext()).apply { setText(item.title.removePrefix("★ ")); inputType = android.text.InputType.TYPE_CLASS_TEXT }
                    MaterialAlertDialogBuilder(requireContext()).setTitle(choices[which]).setView(name)
                        .setPositiveButton("保存") { _, _ ->
                            if (choices[which] == "复制") model.copy(item.id, name.text.toString()) else model.rename(item.id, name.text.toString())
                        }.setNegativeButton("取消", null).show()
                }
                "删除" -> MaterialAlertDialogBuilder(requireContext()).setMessage("删除 ${item.title}？")
                    .setPositiveButton("删除") { _, _ -> model.delete(item.id) }.setNegativeButton("取消", null).show()
                "收藏 / 取消收藏" -> model.favorite(item.id)
                "播放模式" -> MaterialAlertDialogBuilder(requireContext()).setTitle("播放模式")
                    .setItems(arrayOf("单次", "循环（闭合回起点）", "往返")) { _, index -> model.mode(item.id, RouteMode.entries[index]) }.show()
            }
        }.show()
    }
    override fun onResume() { super.onResume(); model.refresh() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("importKind", importKind.name); outState.putString("exportKind", exportKind.name)
        super.onSaveInstanceState(outState)
    }
}
