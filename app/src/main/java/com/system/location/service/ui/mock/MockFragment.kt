package com.system.location.service.ui.mock

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckedTextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tencent.bugly.crashreport.CrashReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.system.location.service.R
import com.system.location.service.android.root.ShellUtils
import com.system.location.service.android.widget.RockerView
import com.system.location.service.android.window.OverlayUtils
import com.system.location.service.databinding.FragmentMockBinding
import com.system.location.service.ext.accuracy
import com.system.location.service.ext.altitude
import com.system.location.service.ext.drawOverOtherAppsEnabled
import com.system.location.service.ext.historicalLocations
import com.system.location.service.ext.hookSensor
import com.system.location.service.ext.needOpenSELinux
import com.system.location.service.ext.rawHistoricalLocations
import com.system.location.service.ext.selectLocation
import com.system.location.service.ext.speed
import com.system.location.service.ui.viewmodel.MockServiceViewModel
import com.system.location.service.ui.viewmodel.MockViewModel

class MockFragment : Fragment() {
    private var _binding: FragmentMockBinding? = null
    private val binding get() = _binding!!

    private val mockViewModel by lazy { ViewModelProvider(this)[MockViewModel::class.java] }
    private val mockServiceViewModel by activityViewModels<MockServiceViewModel>()

    @SuppressLint("SetTextI18n")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMockBinding.inflate(inflater, container, false)

        binding.fabMockLocation.setOnClickListener {
            if (!OverlayUtils.hasOverlayPermissions(requireContext())) {
                Toast.makeText(requireContext(), "请授权悬浮窗权限", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
        }

        binding.fabMockLocation.setOnLongClickListener {
            Toast.makeText(requireContext(), "糸守町", Toast.LENGTH_SHORT).show()
            true
        }

        if (mockServiceViewModel.isServiceStart()) {
            binding.switchMock.text = "停止模拟"
            ContextCompat.getDrawable(requireContext(), R.drawable.rounded_play_disabled_24)?.let {
                binding.switchMock.icon = it
            }
        }

        binding.switchMock.setOnClickListener {
           if (mockServiceViewModel.isServiceStart()) {
                tryCloseService(it as MaterialButton)
            } else {
                tryOpenService(it as MaterialButton)
            }
        }

        with(mockServiceViewModel) {
            if (rocker.isStart) {
                binding.rocker.toggle()
            }
            binding.rocker.setOnClickListener {
                if (locationManager == null) {
                    Toast.makeText(requireContext(), "定位服务加载异常", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (!isServiceStart()) {
                    Toast.makeText(requireContext(), "请先启动模拟", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val checkedTextView = it as CheckedTextView
                checkedTextView.toggle()

                if (!requireContext().drawOverOtherAppsEnabled()) {
                    Toast.makeText(requireContext(), "请授权悬浮窗权限", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                lifecycleScope.launch(Dispatchers.Main) {
                    if (checkedTextView.isChecked) {
                        rocker.show()
                    } else {
                        rocker.hide()
                        setMoving(false)
                    }
                }
            }

            rocker.setRockerListener(object: RockerView.Companion.OnMoveListener {
                override fun onAngle(angle: Double) {
                    setBearing(angle)
                }

                override fun onLockChanged(isLocked: Boolean) {
                    isRockerLocked = isLocked
                }

                override fun onFinished() {
                    if (!isRockerLocked) {
                        setMoving(false)
                    }
                }

                override fun onStarted() {
                    setMoving(true)
                }
            })
        }

        requireContext().selectLocation?.let {
            binding.mockLocationName.text = it.name
            binding.mockLocationAddress.text = it.address
            binding.mockLocationLatlon.text = it.lat.toString().take(8) + ", " + it.lon.toString().take(8)
            mockServiceViewModel.selectedLocation = it
        }

        val locations = requireContext().historicalLocations

        binding.mockLocationCard.setOnClickListener {
            Toast.makeText(requireContext(), mockServiceViewModel.runtimeState.value.toString(), Toast.LENGTH_SHORT).show()
        }

        // 2024.10.10: sort historical locations
        val historicalLocationAdapter = HistoricalLocationAdapter(locations.sortedBy { it.name }.toMutableList()) { loc, isLongClick ->
            if (isLongClick) {
                Toast.makeText(requireContext(), "长按", Toast.LENGTH_SHORT).show()
            } else {
                binding.mockLocationName.text = loc.name
                binding.mockLocationAddress.text = loc.address
                binding.mockLocationLatlon.text = loc.lat.toString().take(8) + ", " + loc.lon.toString().take(8)
                mockServiceViewModel.selectedLocation = loc
                requireContext().selectLocation = loc

                showToast("已选择位置；点击开始后应用新场景")

            }
        }
        val recyclerView = binding.historicalLocationList
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = historicalLocationAdapter
        ItemTouchHelper(object: ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                return false
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                val location = historicalLocationAdapter[position]
                with(requireContext()) {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("删除位置")
                        .setMessage("确定要删除位置(${location.name})吗？")
                        .setPositiveButton("删除") { _, _ ->
                            historicalLocationAdapter.removeItem(position)
                            rawHistoricalLocations = rawHistoricalLocations.toMutableSet().apply {
                                removeIf { runCatching { HistoricalLocation.fromString(it) }.getOrNull() == location }
                            }
                            showToast("已删除位置")
                        }
                        .setNegativeButton("取消", { _, _ ->
                            historicalLocationAdapter.notifyItemChanged(position)
                        })
                        .show()
                }
            }
        }).attachToRecyclerView(recyclerView)

        return binding.root
    }

    private fun tryOpenService(button: MaterialButton) {
        val selected = mockServiceViewModel.selectedLocation ?: run { showToast("请先选择位置"); return }
        lifecycleScope.launch {
            button.isEnabled = false
            try {
                if (mockServiceViewModel.startPoint(selected.lat to selected.lon, selected.name)) {
                    updateMockButtonState(button, "停止模拟", R.drawable.rounded_play_disabled_24)
                } else showToast(mockServiceViewModel.failureMessage())
            } finally { button.isEnabled = true }
        }
    }

    private fun tryCloseService(button: MaterialButton) {
        lifecycleScope.launch {
            button.isEnabled = false
            try {
                if (mockServiceViewModel.stopScenario()) {
                    updateMockButtonState(button, "开始模拟", R.drawable.rounded_play_arrow_24)
                    if (mockServiceViewModel.rocker.isStart) mockServiceViewModel.rocker.hide()
                } else showToast(mockServiceViewModel.failureMessage())
            } finally { button.isEnabled = true }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                mockServiceViewModel.runtimeState.collect { state ->
                    binding.switchMock.text = if (state.isActive) "停止模拟" else "开始模拟"
                    binding.switchMock.setIconResource(if (state.isActive) R.drawable.rounded_play_disabled_24 else R.drawable.rounded_play_arrow_24)
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun showToast(message: String) = lifecycleScope.launch(Dispatchers.Main) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private fun updateMockButtonState(button: MaterialButton, text: String, iconRes: Int) = lifecycleScope.launch(Dispatchers.Main) {
        button.text = text
        ContextCompat.getDrawable(requireContext(), iconRes)?.let {
            button.icon = it
        }
    }
} //
