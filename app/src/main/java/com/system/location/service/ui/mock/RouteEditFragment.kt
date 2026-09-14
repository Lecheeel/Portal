package com.system.location.service.ui.mock

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.model.*
import com.amap.api.services.help.Inputtips
import com.amap.api.services.help.InputtipsQuery
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.system.location.service.R
import com.system.location.service.core.geo.*
import com.system.location.service.core.planning.*
import com.system.location.service.core.scenario.RouteMode
import com.system.location.service.databinding.FragmentRouteEditBinding
import com.system.location.service.ext.lastKnownLat
import com.system.location.service.ext.lastKnownLng
import com.system.location.service.ui.viewmodel.RouteEditViewModel
import kotlinx.coroutines.launch

/** Owns only its map instance, renders durable draft state and sends editor events. */
class RouteEditFragment : Fragment(R.layout.fragment_route_edit) {
    private var binding: FragmentRouteEditBinding? = null
    private val model by viewModels<RouteEditViewModel>()
    private var cameraRestored = false
    private var renderedGeometry: Triple<Gcj02?, Gcj02?, String?>? = null
    private var rendering = false
    private var searchGeneration = 0L
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val ui = FragmentRouteEditBinding.bind(view)
        binding = ui
        cameraRestored = false
        renderedGeometry = null
        ui.amapView.onCreate(savedInstanceState)
        ui.amapView.map.apply {
            uiSettings.isZoomControlsEnabled = true
            uiSettings.isCompassEnabled = true
            uiSettings.isScaleControlsEnabled = true
            setOnMapClickListener { model.pick(Gcj02(it.latitude, it.longitude)) }
            setOnCameraChangeListener(object : AMap.OnCameraChangeListener {
                override fun onCameraChange(camera: CameraPosition?) = Unit
                override fun onCameraChangeFinish(camera: CameraPosition?) {
                    if (cameraRestored && camera != null) model.camera(MapCamera(
                        Gcj02(camera.target.latitude, camera.target.longitude), camera.zoom, camera.bearing, camera.tilt))
                }
            })
        }
        ui.endpoint.setOnCheckedChangeListener { _, checked ->
            if (!rendering) model.select(if (checked == R.id.select_start) Endpoint.START else Endpoint.END)
        }
        ui.plan.setOnClickListener { model.plan() }
        ui.cancel.setOnClickListener { ++searchGeneration; model.cancel() }
        ui.search.setOnClickListener { search(ui.query.text.toString()) }
        ui.confirm.setOnClickListener { confirmSnapshot() }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { model.state.collect(::render) }
                launch { model.message.collect { message ->
                    if (message != null) { Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show(); model.clearMessage() }
                } }
            }
        }
    }
    private fun render(draft: RouteDraft) {
        val ui = binding ?: return
        if (!cameraRestored && draft.message != "正在恢复草稿") {
            val camera = draft.camera ?: MapCamera(CoordinateTransform.toGcj02(Wgs84(requireContext().lastKnownLat,
                requireContext().lastKnownLng)), 15f, 0f, 0f)
            ui.amapView.map.moveCamera(CameraUpdateFactory.newCameraPosition(CameraPosition(
                LatLng(camera.target.latitude, camera.target.longitude), camera.zoom, camera.tilt, camera.bearing)))
            cameraRestored = true
        }
        rendering = true
        ui.endpoint.check(if (draft.selecting == Endpoint.START) R.id.select_start else R.id.select_end)
        rendering = false
        ui.status.text = "起点：${draft.start ?: "未选择"}\n终点：${draft.end ?: "未选择"}\n" +
            (if (draft.phase == DraftPhase.PLANNING) "正在规划步行路线…" else draft.message.orEmpty())
        ui.confirm.isEnabled = draft.phase == DraftPhase.PLANNED && draft.route != null
        ui.plan.isEnabled = draft.start != null && draft.end != null
        val geometry = Triple(draft.start, draft.end, draft.route?.id)
        if (geometry == renderedGeometry) return
        renderedGeometry = geometry
        ui.amapView.map.clear()
        draft.start?.let { ui.amapView.map.addMarker(MarkerOptions().position(LatLng(it.latitude, it.longitude)).title("起点")) }
        draft.end?.let { ui.amapView.map.addMarker(MarkerOptions().position(LatLng(it.latitude, it.longitude)).title("终点")) }
        draft.route?.let { route ->
            val points = route.points.map { CoordinateTransform.toGcj02(it).let { p -> LatLng(p.latitude, p.longitude) } }
            ui.amapView.map.addPolyline(PolylineOptions().addAll(points).width(10f).color(Color.rgb(30, 110, 230)))
            // Keep the user's camera during restores/rotation; full route can be inspected by zooming.
        }
    }
    private fun search(query: String) {
        if (query.isBlank()) return
        val generation = ++searchGeneration
        try {
            Inputtips(requireContext(), InputtipsQuery(query, "")).apply {
                setInputtipsListener { tips, code ->
                    if (generation != searchGeneration || binding == null || !isAdded) return@setInputtipsListener
                    val candidates = tips.orEmpty().filter { it.point != null }
                    if (code != 1000 || candidates.isEmpty()) {
                        Toast.makeText(requireContext(), "未找到地点（$code），请重试或直接点击地图", Toast.LENGTH_LONG).show()
                    } else MaterialAlertDialogBuilder(requireContext()).setTitle("选择地点")
                        .setItems(candidates.map { "${it.name} ${it.address.orEmpty()}" }.toTypedArray()) { _, which ->
                            if (generation != searchGeneration || binding == null) return@setItems
                            val point = candidates[which].point
                            model.pick(Gcj02(point.latitude, point.longitude))
                            binding?.amapView?.map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(point.latitude, point.longitude), 16f))
                        }.show()
                }
                requestInputtipsAsyn()
            }
        } catch (error: Exception) { Toast.makeText(requireContext(), "地点搜索失败：${error.message}", Toast.LENGTH_LONG).show() }
    }
    private fun confirmSnapshot() {
        val snapshot = model.state.value.route?.frozen() ?: return
        val container = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(36, 12, 36, 12) }
        val name = EditText(requireContext()).apply { hint = "路线名称"; setText(snapshot.name); inputType = android.text.InputType.TYPE_CLASS_TEXT }
        val mode = Spinner(requireContext()).apply {
            adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, listOf("单次", "循环（闭合回起点）", "往返"))
        }
        container.addView(name); container.addView(mode)
        val dialog = MaterialAlertDialogBuilder(requireContext()).setTitle("确认 ${snapshot.points.size} 个路线点")
            .setMessage("将保存当前完整路线。运行中的场景使用确认时的快照。")
            .setView(container).setPositiveButton("保存", null).setNeutralButton("保存并启动", null).setNegativeButton("取消", null).create()
        dialog.setOnShowListener {
            fun save(start: Boolean) {
                val title = name.text.toString().trim()
                if (title.isBlank()) { name.error = "名称不能为空"; return }
                model.save(snapshot, title, RouteMode.entries[mode.selectedItemPosition], start)
                dialog.dismiss()
            }
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener { save(false) }
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener { save(true) }
        }
        dialog.show()
    }
    override fun onResume() { super.onResume(); binding?.amapView?.onResume() }
    override fun onPause() { binding?.amapView?.onPause(); super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); binding?.amapView?.onSaveInstanceState(outState) }
    override fun onDestroyView() {
        ++searchGeneration
        binding?.amapView?.apply { map.setOnMapClickListener(null); map.setOnCameraChangeListener(null); onDestroy() }
        binding = null
        super.onDestroyView()
    }
}
