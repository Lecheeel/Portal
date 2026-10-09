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
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.system.location.service.R
import com.system.location.service.core.runtime.RuntimePhase
import com.system.location.service.core.scenario.RouteMode
import com.system.location.service.databinding.FragmentRouteMockBinding
import com.system.location.service.runtime.ScenarioRuntime
import com.system.location.service.ui.viewmodel.*
import com.system.location.service.ui.displayLabel
import com.system.location.service.ui.displayStates
import com.system.location.service.ui.setTextIfChanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RouteMockFragment : Fragment(R.layout.fragment_route_mock) {
    private val model by viewModels<LibraryViewModel>()
    private var importKind = LibraryKind.ROUTES
    private var exportKind = LibraryKind.ROUTES
    private var exportRouteId: String? = null
    private val query = MutableStateFlow("")
    private val importGpx = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.importGpx(uri)
    }
    private val exportGpx = registerForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri ->
        if (uri != null) model.exportGpx(uri, exportRouteId)
    }
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
        exportRouteId = savedInstanceState?.getString("exportRouteId")
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val ui = FragmentRouteMockBinding.bind(view)
        if (resources.configuration.screenHeightDp < 480) {
            ui.libraryHeading.visibility = View.GONE
            ui.libraryDescription.visibility = View.GONE
            ui.emptyArtwork.visibility = View.GONE
            ui.emptyDescription.visibility = View.GONE
        }
        val kindIds = listOf(R.id.kind_routes, R.id.kind_scenarios, R.id.kind_locations)
        ui.libraryKind.check(kindIds[model.state.value.kind.ordinal])
        ui.libraryKind.addOnButtonCheckedListener { _, id, checked ->
            if (checked && kindIds.indexOf(id) != model.state.value.kind.ordinal) model.kind(LibraryKind.entries[kindIds.indexOf(id)])
        }
        ui.librarySearch.setText(query.value)
        ui.librarySearch.addTextChangedListener { query.value = it.toString() }
        val adapter = LibraryAdapter(::actions)
        ui.libraryItems.layoutManager = LinearLayoutManager(requireContext())
        ui.libraryItems.adapter = adapter
        ui.libraryItems.itemAnimator = null
        ui.addRoute.setOnClickListener { findNavController().navigate(R.id.nav_route_edit) }
        ui.libraryMore.setOnClickListener {
            val routes = model.state.value.kind == LibraryKind.ROUTES
            val choices = if (routes) arrayOf("导入 JSON", "导出 JSON", "导入 GPX", "导出 GPX") else arrayOf("导入 JSON", "导出 JSON")
            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.ui_library_more).setItems(choices) { _, index ->
                when (index) {
                    0 -> { importKind = model.state.value.kind; importFile.launch(arrayOf("application/json", "text/*")) }
                    1 -> { exportKind = model.state.value.kind; exportFile.launch("location-${exportKind.name.lowercase()}.json") }
                    2 -> importGpx.launch(arrayOf("application/gpx+xml", "application/xml", "text/xml", "application/octet-stream"))
                    3 -> { exportRouteId = null; exportGpx.launch("location-routes.gpx") }
                }
            }.show()
        }
        ui.pauseResume.setOnClickListener { if (ScenarioRuntime.state.value.phase == RuntimePhase.PAUSED) ScenarioRuntime.resume() else ScenarioRuntime.pause() }
        ui.stopScene.setOnClickListener { ScenarioRuntime.stop() }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { model.state.collect { state ->
                    ui.libraryStatus.setTextIfChanged(state.message ?: if (state.busy) "正在读取／保存…" else getString(R.string.ui_library_count, state.items.size))
                    ui.libraryLoading.visibility = if (state.busy) View.VISIBLE else View.GONE
                    ui.libraryKind.isEnabled = !state.busy
                    kindIds.forEach { ui.libraryKind.findViewById<View>(it).isEnabled = !state.busy }
                    if (ui.libraryKind.checkedButtonId != kindIds[state.kind.ordinal]) ui.libraryKind.check(kindIds[state.kind.ordinal])
                    ui.libraryMore.isEnabled = !state.busy
                    ui.addRoute.isEnabled = !state.busy
                    ui.addRoute.visibility = if (state.kind == LibraryKind.ROUTES) View.VISIBLE else View.GONE
                } }
                launch {
                    combine(model.state.map { it.kind to it.items }.distinctUntilChanged(), query) { data, text -> data to text }
                        .mapLatest { (data, text) -> withContext(Dispatchers.Default) {
                            data.second.filter { text.isBlank() || it.title.contains(text, true) || it.detail.contains(text, true) }
                                .map { LibraryRow(data.first, it) }
                        } }.collect { rows ->
                            adapter.submitList(rows)
                            ui.libraryEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
                            ui.emptyTitle.setText(if (query.value.isBlank()) R.string.ui_library_empty else R.string.ui_library_no_match)
                            ui.emptyDescription.setText(if (query.value.isBlank()) R.string.ui_library_empty_desc else R.string.ui_library_no_match_desc)
                        }
                }
                launch { ScenarioRuntime.state.displayStates().collect { state ->
                    ui.libraryRuntimeCard.visibility = if (state.isActive || state.error != null) View.VISIBLE else View.GONE
                    ui.runtimeStatus.setTextIfChanged("${state.backend.displayLabel()} · ${state.phase.displayLabel()} · ${state.scenarioName.orEmpty()}" +
                        (state.error?.let { "\n${it.stage}: ${it.reason}\n${it.suggestion}" } ?: "")
                    )
                    ui.pauseResume.isEnabled = state.phase in setOf(RuntimePhase.RUNNING, RuntimePhase.PAUSED)
                    val action = if (state.phase == RuntimePhase.PAUSED) "继续" else "暂停"
                    if (ui.pauseResume.text != action) {
                        ui.pauseResume.text = action
                        ui.pauseResume.setIconResource(if (state.phase == RuntimePhase.PAUSED) R.drawable.baseline_play_24 else R.drawable.ic_pause)
                    }
                } }
            }
        }
    }
    private fun actions(item: LibraryItem) {
        if (model.state.value.busy || model.state.value.items.none { it.id == item.id }) return
        val choices = mutableListOf("启动", "重命名", "复制", "删除")
        if (model.state.value.kind != LibraryKind.SCENARIOS) choices += "收藏 / 取消收藏"
        if (model.state.value.kind != LibraryKind.LOCATIONS) choices += "播放模式"
        if (model.state.value.kind == LibraryKind.ROUTES) choices += "导出 GPX"
        MaterialAlertDialogBuilder(requireContext()).setTitle(item.title).setItems(choices.toTypedArray()) { _, which ->
            when (choices[which]) {
                "启动" -> model.start(item.id)
                "导出 GPX" -> { exportRouteId = item.id; exportGpx.launch("route.gpx") }
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
        outState.putString("exportRouteId", exportRouteId)
        super.onSaveInstanceState(outState)
    }
}
