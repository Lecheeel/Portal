package com.system.location.service.ui.mock

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import com.google.android.material.tabs.TabLayout
import com.system.location.service.R
import com.system.location.service.ui.gnss.GnssMockFragment

/** One navigation destination, with independent location and satellite controls. */
class SimulationFragment : Fragment(R.layout.fragment_simulation) {
    private var selectedTab = 0

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        selectedTab = savedInstanceState?.getInt(SELECTED_TAB)
            ?: arguments?.getInt(INITIAL_TAB, 0) ?: 0
        val tabs = view.findViewById<TabLayout>(R.id.simulation_tabs)
        tabs.addTab(tabs.newTab().setText(R.string.simulation_location_tab), false)
        tabs.addTab(tabs.newTab().setText(R.string.simulation_satellite_tab), false)
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) { select(tab.position) }
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        tabs.selectTab(tabs.getTabAt(selectedTab.coerceIn(0, 1)))
    }

    private fun select(position: Int) {
        selectedTab = position
        val tag = "simulation_tab_$position"
        val active = childFragmentManager.findFragmentByTag(tag)
            ?: if (position == 0) MockFragment() else GnssMockFragment()
        childFragmentManager.beginTransaction().apply {
            childFragmentManager.fragments.filter { it !== active }.forEach {
                hide(it)
                setMaxLifecycle(it, Lifecycle.State.CREATED)
            }
            if (!active.isAdded) add(R.id.simulation_content, active, tag) else show(active)
            setMaxLifecycle(active, Lifecycle.State.RESUMED)
            setPrimaryNavigationFragment(active)
        }.commitNow()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(SELECTED_TAB, selectedTab)
        super.onSaveInstanceState(outState)
    }

    companion object {
        const val INITIAL_TAB = "initialTab"
        private const val SELECTED_TAB = "selectedSimulationTab"
    }
}
