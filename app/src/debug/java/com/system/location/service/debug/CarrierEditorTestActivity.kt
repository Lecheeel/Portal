package com.system.location.service.debug

import android.os.Bundle
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import androidx.navigation.fragment.NavHostFragment
import com.system.location.service.R

/** Debug-only UI fixture: real settings navigation without initializing the home map SDK. */
class CarrierEditorTestActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        setContentView(FrameLayout(this).apply { id = R.id.nav_host_fragment_content_main })
        if (savedInstanceState == null) {
            val host = NavHostFragment()
            supportFragmentManager.beginTransaction()
                .replace(R.id.nav_host_fragment_content_main, host)
                .setPrimaryNavigationFragment(host).commitNow()
            val graph = host.navController.navInflater.inflate(R.navigation.mobile_navigation)
            graph.setStartDestination(R.id.nav_carrier_identity)
            host.navController.graph = graph
        }
    }
}
