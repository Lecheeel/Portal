package com.system.location.service.ui.mock

import android.os.Bundle
import android.view.View
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.system.location.service.R
import com.system.location.service.core.runtime.RuntimePhase
import com.system.location.service.databinding.FragmentMockBinding
import com.system.location.service.runtime.ScenarioRuntime
import com.system.location.service.ui.viewmodel.PointViewModel
import kotlinx.coroutines.launch

class MockFragment : Fragment(R.layout.fragment_mock) {
    private val model by viewModels<PointViewModel>()
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val ui = FragmentMockBinding.bind(view)
        ui.pointName.setText(model.name); ui.latitude.setText(model.latitude); ui.longitude.setText(model.longitude)
        ui.pointName.addTextChangedListener { model.name = it.toString() }
        ui.latitude.addTextChangedListener { model.latitude = it.toString() }
        ui.longitude.addTextChangedListener { model.longitude = it.toString() }
        ui.startPoint.setOnClickListener { model.saveOrStart(true) }
        ui.savePoint.setOnClickListener { model.saveOrStart(false) }
        ui.library.setOnClickListener { findNavController().navigate(R.id.nav_route_gallery) }
        ui.stopPoint.setOnClickListener { ScenarioRuntime.stop() }
        ui.pauseResume.setOnClickListener { if (ScenarioRuntime.state.value.phase == RuntimePhase.PAUSED) ScenarioRuntime.resume() else ScenarioRuntime.pause() }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { model.message.collect { ui.message.text = it.orEmpty() } }
                launch { ScenarioRuntime.state.collect { state ->
                    ui.pointStatus.text = "${state.backend} · ${state.phase}\n${state.scenarioName.orEmpty()}\n" +
                        (state.sample?.let { "${it.latitude}, ${it.longitude}" } ?: "") +
                        (state.error?.let { "\n${it.stage}: ${it.reason}\n${it.suggestion}" } ?: "")
                    ui.pauseResume.isEnabled = state.phase in setOf(RuntimePhase.RUNNING, RuntimePhase.PAUSED)
                    ui.pauseResume.text = if (state.phase == RuntimePhase.PAUSED) "恢复" else "暂停"
                } }
            }
        }
    }
}
