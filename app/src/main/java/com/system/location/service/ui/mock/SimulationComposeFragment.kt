package com.system.location.service.ui.mock

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import com.system.location.service.ui.navigation.PortalFragmentTab
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import com.system.location.service.R
import com.system.location.service.designsystem.theme.PortalTheme
import com.system.location.service.ui.gnss.GnssComposeFragment
import com.system.location.service.ui.environment.EnvironmentComposeFragment

/**
 * Compose layout owns the tabs; AndroidFragment retains the bodies' controller lifetimes.
 */
class SimulationComposeFragment : Fragment() {
    private var selectedTab = 0
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedTab = savedInstanceState?.getInt(KEY_SELECTED)
            ?: arguments?.getInt(INITIAL_TAB, 0) ?: 0
        selectedTab = selectedTab.coerceIn(0, 1)
        // Saved tasks from the hand-built container used fixed IDs absent from the new layout.
        val old = childFragmentManager.fragments.filter {
            it.tag in setOf("simulation_location_compose", "simulation_satellite_compose")
        }
        if (old.isNotEmpty()) childFragmentManager.beginTransaction().apply {
            old.forEach(::remove)
        }.commitNow()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            PortalTheme {
                var selected by rememberSaveable { mutableIntStateOf(selectedTab) }
                Column(Modifier.fillMaxSize()) {
                    SimulationScreen(selected) { selected = it; selectedTab = it }
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        PortalFragmentTab<EnvironmentComposeFragment>(selected == 0, Modifier.fillMaxSize())
                        PortalFragmentTab<GnssComposeFragment>(selected == 1, Modifier.fillMaxSize())
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(KEY_SELECTED, selectedTab)
        super.onSaveInstanceState(outState)
    }

    companion object {
        const val INITIAL_TAB = "initialTab"
        private const val KEY_SELECTED = "selectedSimulationTab"
    }
}

@Composable
private fun SimulationScreen(
    initialTab: Int,
    onTabSelected: (Int) -> Unit,
) {
    var selected by rememberSaveable { mutableIntStateOf(initialTab.coerceIn(0, 1)) }
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxWidth()) {
            PrimaryTabRow(
                selectedTabIndex = selected,
                containerColor = MaterialTheme.colorScheme.background,
                divider = {},
            ) {
                listOf("环境", "卫星").forEachIndexed { index, title ->
                    Tab(
                        selected = selected == index,
                        onClick = {
                            if (selected != index) {
                                selected = index
                                onTabSelected(index)
                            }
                        },
                        text = { Text(title, style = MaterialTheme.typography.titleSmall) },
                        icon = {
                            Icon(
                                painterResource(if (index == 0) R.drawable.ic_layers_modern else R.drawable.baseline_satellite_alt_24),
                                contentDescription = null,
                                modifier = Modifier.size(22.dp),
                            )
                        },
                    )
                }
            }

        }
    }
}

