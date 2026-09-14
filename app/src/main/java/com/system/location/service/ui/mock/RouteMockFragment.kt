package com.system.location.service.ui.mock

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.CheckedTextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.Navigation
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.system.location.service.R
import com.system.location.service.android.root.ShellUtils
import com.system.location.service.android.widget.RockerView
import com.system.location.service.android.window.OverlayUtils
import com.system.location.service.databinding.FragmentRouteMockBinding
import com.system.location.service.ext.accuracy
import com.system.location.service.ext.altitude
import com.system.location.service.ext.drawOverOtherAppsEnabled
import com.system.location.service.ext.hookSensor
import com.system.location.service.ext.jsonHistoricalRoutes
import com.system.location.service.ext.needOpenSELinux
import com.system.location.service.ext.selectRoute
import com.system.location.service.ext.speed
import com.system.location.service.ui.viewmodel.HomeViewModel
import com.system.location.service.ui.viewmodel.MockServiceViewModel
import androidx.navigation.findNavController

class RouteMockFragment : Fragment() {
    private var _binding: FragmentRouteMockBinding? = null
    private val binding get() = _binding!!

    private val routeMockViewModel by viewModels<HomeViewModel>()
    private val mockServiceViewModel by activityViewModels<MockServiceViewModel>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRouteMockBinding.inflate(inflater, container, false)

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
            rocker.setRockerListener(object : RockerView.Companion.OnMoveListener {
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
            rocker.setRockerAutoListener(object : Rocker.Companion.OnAutoListener {
                override fun onAutoPlay(isPlay: Boolean) {
                    if (isPlay) {
                        resumeScenario()
                    } else {
                        pauseScenario()
                    }
                }

                override fun onAutoLock(isLock: Boolean) {

                }
            })
        }

        requireContext().selectRoute?.let {
            binding.mockRouteName.text = it.name
            mockServiceViewModel.selectedRoute = it
        }


        binding.fab.setOnClickListener { view ->
            val subFabList = arrayOf(
                binding.fabAddRoute
            )

            if (!routeMockViewModel.mFabOpened) {
                routeMockViewModel.mFabOpened = true

                val rotateMainFab = ObjectAnimator.ofFloat(view, "rotation", 0f, 90f)
                rotateMainFab.duration = 200

                val animators = arrayListOf<ObjectAnimator>()
                animators.add(rotateMainFab)
                subFabList.forEachIndexed { index, fab ->
                    fab.visibility = View.VISIBLE
                    fab.alpha = 1f
                    fab.scaleX = 1f
                    fab.scaleY = 1f
                    val translationX =
                        ObjectAnimator.ofFloat(fab, "translationX", 0f, 20f + index * 8f)
                    translationX.duration = 200
                    animators.add(translationX)
                }

                val animatorSet = AnimatorSet()
                animatorSet.playTogether(animators.toList())
                animatorSet.interpolator = DecelerateInterpolator()
                animatorSet.addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        view.isClickable = true
                    }
                })
                view.isClickable = false
                animatorSet.start()
            } else {
                routeMockViewModel.mFabOpened = false

                val rotateMainFab = ObjectAnimator.ofFloat(view, "rotation", 90f, 0f)
                rotateMainFab.duration = 200

                val animators = arrayListOf<ObjectAnimator>()
                animators.add(rotateMainFab)
                subFabList.forEachIndexed { index, fab ->
                    val transX = ObjectAnimator.ofFloat(fab, "translationX", 0f, -20f - index * 8f)
                    transX.duration = 150
                    val scaleX = ObjectAnimator.ofFloat(fab, "scaleX", 1f, 0f)
                    scaleX.duration = 200
                    val scaleY = ObjectAnimator.ofFloat(fab, "scaleY", 1f, 0f)
                    scaleY.duration = 200
                    val alpha = ObjectAnimator.ofFloat(fab, "alpha", 1f, 0f)
                    alpha.duration = 200
                    animators.add(transX)
                    animators.add(scaleX)
                    animators.add(scaleY)
                    animators.add(alpha)
                }

                val animatorSet = AnimatorSet()
                animatorSet.playTogether(animators.toList())
                animatorSet.interpolator = DecelerateInterpolator()
                animatorSet.addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        subFabList.forEach { it.visibility = View.GONE }
                        view.isClickable = true
                    }
                })
                view.isClickable = false
                animatorSet.start()
            }
        }

        binding.fabAddRoute.setOnClickListener {
            activity?.findNavController(R.id.nav_host_fragment_content_main)?.navigate(R.id.nav_route_edit)
        }

        var locations = requireContext().jsonHistoricalRoutes
        // 如果locations是空字符串，则创建默认
        if (locations.isEmpty()) {
            val defaultRoute = HistoricalRoute(
                "默认路线",
                mutableListOf(Pair(39.908822, 116.397465), Pair(39.907951, 116.397500))
            )
            val defaultRoutes = mutableListOf(defaultRoute)
            requireContext().jsonHistoricalRoutes = RouteJson.encodeRoutes(defaultRoutes)
            locations = requireContext().jsonHistoricalRoutes
        }
        val routes = try {
            RouteJson.decodeRoutes(locations)
        } catch (e: IllegalArgumentException) {
            showToast("历史路线数据格式错误")
            emptyList()
        }

        val historicalRouteAdapter = HistoricalRouteAdapter(routes.sortedBy { it.name }
            .toMutableList()) { route, isLongClick ->
            if (isLongClick) {
                Toast.makeText(requireContext(), "长按", Toast.LENGTH_SHORT).show()
            } else {
                binding.mockRouteName.text = route.name
                mockServiceViewModel.selectedRoute = route
                requireContext().selectRoute = route

                showToast("已选择路线；点击开始后使用新快照，当前场景不受编辑影响")

            }
        }


        val recyclerView = binding.historicalRouteList
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = historicalRouteAdapter

        ItemTouchHelper(object :
            ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                return false
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                val location = historicalRouteAdapter[position]
                with(requireContext()) {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("删除路线")
                        .setMessage("确定要删除路线(${location.name})吗？")
                        .setPositiveButton("删除") { _, _ ->
                            historicalRouteAdapter.removeItem(position)
                            RouteJson.decodeRoutes(jsonHistoricalRoutes)
                                .toMutableList().apply {
                                    removeIf { it.name == location.name }
                                }.let {
                                    jsonHistoricalRoutes = RouteJson.encodeRoutes(it)
                                }
                            showToast("已删除路线")
                        }
                        .setNegativeButton("取消", { _, _ ->
                            historicalRouteAdapter.notifyItemChanged(position)
                        })
                        .show()
                }
            }
        }).attachToRecyclerView(recyclerView)

        return binding.root
    }


    private fun tryOpenService(button: MaterialButton) {
        val selected = mockServiceViewModel.selectedRoute ?: run { showToast("请先选择路线"); return }
        lifecycleScope.launch {
            button.isEnabled = false
            try {
                if (mockServiceViewModel.startRoute(selected)) {
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

    override fun onDestroyView() { super.onDestroyView(); _binding = null }

    private fun showToast(message: String) = lifecycleScope.launch(Dispatchers.Main) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private fun updateMockButtonState(button: MaterialButton, text: String, iconRes: Int) =
        lifecycleScope.launch(Dispatchers.Main) {
            button.text = text
            ContextCompat.getDrawable(requireContext(), iconRes)?.let {
                button.icon = it
            }
        }

}
