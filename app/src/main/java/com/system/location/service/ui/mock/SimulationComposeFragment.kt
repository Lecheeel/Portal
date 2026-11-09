package com.system.location.service.ui.mock

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentContainerView
import androidx.lifecycle.Lifecycle
import com.system.location.service.R
import com.system.location.service.designsystem.theme.PortalTheme
import com.system.location.service.ui.gnss.GnssComposeFragment

/**
 * Compose tab shell for the simulation screen. Both tab bodies are Compose fragments hosted in a
 * FragmentContainerView; this removes the old XML TabLayout without rewriting tab navigation.
 */
class SimulationComposeFragment : Fragment() {
    private var selectedTab = 0
    private var contentId = 0
    private var tabsReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedTab = savedInstanceState?.getInt(KEY_SELECTED)
            ?: arguments?.getInt(INITIAL_TAB, 0) ?: 0
        contentId = View.generateViewId()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            PortalTheme {
                SimulationScreen(
                    initialTab = selectedTab,
                    contentId = contentId,
                    onTabSelected = {
                        selectedTab = it
                        showTab(it)
                    },
                )
            }
        }
        post {
            ensureTabs()
            showTab(selectedTab)
        }
    }

    private fun ensureTabs() {
        if (tabsReady || !isAdded) return
        val mock = childFragmentManager.findFragmentByTag(TAG_LOCATION) ?: MockComposeFragment().also {
            childFragmentManager.beginTransaction().add(contentId, it, TAG_LOCATION).hide(it).commitNow()
        }
        val satellite = childFragmentManager.findFragmentByTag(TAG_SATELLITE) ?: GnssComposeFragment().also {
            childFragmentManager.beginTransaction().add(contentId, it, TAG_SATELLITE).hide(it).commitNow()
        }
        // The transaction above may not have attached the first fragment before commitNow returns.
        if (!mock.isAdded) childFragmentManager.beginTransaction().add(contentId, mock, TAG_LOCATION).hide(mock).commitNow()
        if (!satellite.isAdded) childFragmentManager.beginTransaction().add(contentId, satellite, TAG_SATELLITE).hide(satellite).commitNow()
        tabsReady = true
    }

    private fun showTab(position: Int) {
        if (!tabsReady) return
        val fragment = childFragmentManager.findFragmentByTag(if (position == 0) TAG_LOCATION else TAG_SATELLITE) ?: return
        childFragmentManager.beginTransaction().apply {
            childFragmentManager.fragments.forEach { other ->
                if (other !== fragment) {
                    hide(other)
                    setMaxLifecycle(other, Lifecycle.State.CREATED)
                }
            }
            show(fragment)
            setMaxLifecycle(fragment, Lifecycle.State.RESUMED)
            setPrimaryNavigationFragment(fragment)
        }.commitNow()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(KEY_SELECTED, selectedTab)
        super.onSaveInstanceState(outState)
    }

    companion object {
        const val INITIAL_TAB = "initialTab"
        private const val KEY_SELECTED = "selectedSimulationTab"
        private const val TAG_LOCATION = "simulation_location_compose"
        private const val TAG_SATELLITE = "simulation_satellite_compose"
    }
}

@Composable
private fun SimulationScreen(
    initialTab: Int,
    contentId: Int,
    onTabSelected: (Int) -> Unit,
) {
    var selected by rememberSaveable { mutableIntStateOf(initialTab.coerceIn(0, 1)) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            TabRow(selectedTabIndex = selected) {
                listOf("位置", "卫星").forEachIndexed { index, title ->
                    Tab(
                        selected = selected == index,
                        onClick = {
                            selected = index
                            onTabSelected(index)
                        },
                        text = { Text(title) },
                    )
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                AndroidView(
                    factory = { context ->
                        FragmentContainerView(context).apply { id = contentId }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

