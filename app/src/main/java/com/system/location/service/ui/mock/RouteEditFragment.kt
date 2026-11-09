package com.system.location.service.ui.mock

import android.graphics.Color
import android.graphics.Point
import android.os.Bundle
import android.view.View
import android.view.MotionEvent
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.system.location.service.maps.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.system.location.service.R
import com.system.location.service.core.geo.*
import com.system.location.service.core.planning.*
import com.system.location.service.core.scenario.RouteMode
import com.system.location.service.databinding.FragmentRouteEditBinding
import com.system.location.service.ext.lastKnownLat
import com.system.location.service.ext.lastKnownLng
import com.system.location.service.ui.CallbackScheduler
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
    private var strokeCoordinator: StrokePreviewCoordinator<Gcj02, Gcj02>? = null
    private var strokePreview: MapLine? = null
    private var lastScreenPoint: Point? = null
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val ui = FragmentRouteEditBinding.bind(view)
        binding = ui
        cameraRestored = false
        renderedGeometry = null
        ui.amapView.onCreate(savedInstanceState)
        ui.amapView.onClick { model.pick(it) }
        ui.amapView.onCamera { if (cameraRestored) model.camera(it) }
        ui.endpoint.setOnCheckedChangeListener { _, checked ->
            if (!rendering) model.select(if (checked == R.id.select_start) Endpoint.START else Endpoint.END)
        }
        ui.drawingMode.addOnButtonCheckedListener { _, id, checked ->
            if (!rendering && checked) {
                val mode = when (id) { R.id.mode_points -> DrawingMode.POINTS; R.id.mode_freehand -> DrawingMode.FREEHAND; else -> DrawingMode.PLAN }
                if (mode != model.state.value.mode) {
                    rendering = true
                    ui.drawingMode.check(modeId(model.state.value.mode))
                    rendering = false
                    if (model.state.value.start != null) MaterialAlertDialogBuilder(requireContext())
                        .setTitle("切换绘制方式？").setMessage("当前未保存的路线将被清空。")
                        .setNegativeButton("继续编辑", null).setPositiveButton("切换") { _, _ -> ++searchGeneration; model.mode(mode) }.show()
                    else { ++searchGeneration; model.mode(mode) }
                }
            }
        }
        strokeCoordinator = StrokePreviewCoordinator(
            frames = object : CallbackScheduler {
                override fun post(delayMillis: Long, callback: Runnable) { ui.gestureLayer.postOnAnimation(callback) }
                override fun remove(callback: Runnable) { ui.gestureLayer.removeCallbacks(callback) }
            },
            convert = { point -> point },
            render = { points ->
                if (binding === ui) {
                    if (strokePreview == null) strokePreview = ui.amapView.line(points, Color.rgb(16, 185, 129))
                    else strokePreview?.points(points)
                }
            },
            clearPreview = { strokePreview?.remove(); strokePreview = null },
            commit = { points -> model.stroke(points) },
        )
        ui.gestureLayer.onStrokeTouch = ::drawStroke
        ui.undo.setOnClickListener { model.undo() }
        ui.plan.setOnClickListener { model.plan() }
        ui.cancel.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext()).setTitle("清空当前路线？")
                .setNegativeButton("取消", null).setPositiveButton("清空") { _, _ -> ++searchGeneration; model.cancel() }.show()
        }
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
            ui.amapView.camera(camera)
            cameraRestored = true
        }
        rendering = true
        ui.drawingMode.check(modeId(draft.mode))
        ui.endpoint.check(if (draft.selecting == Endpoint.START) R.id.select_start else R.id.select_end)
        rendering = false
        val planned = draft.mode == DrawingMode.PLAN
        ui.endpoint.visibility = if (planned) View.VISIBLE else View.GONE
        ui.plan.visibility = if (planned) View.VISIBLE else View.GONE
        ui.undo.visibility = if (planned) View.GONE else View.VISIBLE
        ui.undo.isEnabled = draft.strokes.isNotEmpty()
        ui.gestureLayer.drawingEnabled = draft.mode == DrawingMode.FREEHAND
        if (!ui.gestureLayer.drawingEnabled) discardStroke()
        ui.gestureHint.text = when (draft.mode) {
            DrawingMode.PLAN -> "点击地图设置起终点，沿道路规划步行路线"
            DrawingMode.POINTS -> "依次点击地图加点，按顺序直线连接；拖动和双指缩放地图"
            DrawingMode.FREEHAND -> "单指画线 · 双指移动和缩放 · 撤销上一笔\n多笔之间直线连接；双指操作会取消尚未完成的一笔"
        }
        ui.status.text = if (draft.phase == DraftPhase.PLANNING) "正在规划步行路线…"
            else draft.message ?: if (planned) "起点${if (draft.start == null) "未选" else "已选"} · 终点${if (draft.end == null) "未选" else "已选"}"
            else "从地图开始绘制你的路线"
        ui.confirm.isEnabled = draft.phase == DraftPhase.PLANNED && draft.route != null
        ui.plan.isEnabled = draft.start != null && draft.end != null && draft.phase != DraftPhase.PLANNING
        val geometry = Triple(draft.start, draft.end, draft.route?.id)
        if (geometry == renderedGeometry) return
        renderedGeometry = geometry
        ui.amapView.clear()
        draft.start?.let { ui.amapView.marker(it, "起点") }
        draft.end?.let { ui.amapView.marker(it, "终点") }
        draft.route?.let { route ->
            val points = route.points.map { CoordinateTransform.toGcj02(it) }
            ui.amapView.line(points, Color.rgb(30, 110, 230))
            // Keep the user's camera during restores/rotation; full route can be inspected by zooming.
        }
    }
    private fun modeId(mode: DrawingMode) = when (mode) {
        DrawingMode.PLAN -> R.id.mode_plan
        DrawingMode.POINTS -> R.id.mode_points
        DrawingMode.FREEHAND -> R.id.mode_freehand
    }

    private fun drawStroke(event: MotionEvent) {
        val ui = binding ?: return
        val stroke = strokeCoordinator ?: return
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) { discardStroke(); return }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) discardStroke()
        val screen = Point(event.x.toInt(), event.y.toInt())
        val last = lastScreenPoint
        val threshold = 6 * resources.displayMetrics.density
        if (last == null || kotlin.math.hypot((screen.x - last.x).toDouble(), (screen.y - last.y).toDouble()) >= threshold || event.actionMasked == MotionEvent.ACTION_UP) {
            // Keep the projection's LatLng; preview frames reuse it without remapping the stroke.
            stroke.add(ui.amapView.project(screen))
            lastScreenPoint = screen
        }
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            stroke.finish()
            lastScreenPoint = null
        }
    }

    private fun discardStroke() {
        strokeCoordinator?.cancel()
        lastScreenPoint = null
    }

    private fun search(query: String) {
        if (query.isBlank()) return
        val generation = ++searchGeneration
        val ui = binding ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val candidates = MapServices(requireContext(), ui.amapView.provider).suggestions(query)
                if (generation != searchGeneration || binding !== ui || !isAdded) return@launch
                if (candidates.isEmpty()) Toast.makeText(requireContext(), "未找到地点，请直接点击地图", Toast.LENGTH_LONG).show()
                else MaterialAlertDialogBuilder(requireContext()).setTitle("选择地点")
                    .setItems(candidates.map { "${it.name} ${it.address}" }.toTypedArray()) { _, which ->
                        if (generation != searchGeneration || binding !== ui) return@setItems
                        val point = candidates[which].point
                        ++searchGeneration
                        if (model.state.value.mode != DrawingMode.FREEHAND) model.pick(point)
                        ui.amapView.move(point, 16f, true)
                    }.show()
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (generation == searchGeneration && binding === ui) Toast.makeText(requireContext(), error.message ?: "地点搜索失败", Toast.LENGTH_LONG).show()
            }
        }
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
    override fun onStart() { super.onStart(); binding?.amapView?.onStart() }
    override fun onStop() { binding?.amapView?.onStop(); super.onStop() }
    override fun onResume() { super.onResume(); binding?.amapView?.onResume() }
    override fun onPause() { binding?.amapView?.onPause(); super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); binding?.amapView?.onSaveInstanceState(outState) }
    override fun onDestroyView() {
        ++searchGeneration
        binding?.gestureLayer?.onStrokeTouch = null
        discardStroke()
        strokeCoordinator = null
        binding?.amapView?.onDestroy()
        binding = null
        super.onDestroyView()
    }
}
