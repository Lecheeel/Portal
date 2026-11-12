package com.system.location.service.ui.navigation

import android.content.Context
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.navigation.fragment.NavHostFragment
import androidx.transition.Fade

/** AndroidX Fade supports seeking, so a cancelled Back gesture can restore the page. */
internal class PortalNavigationTransitions : FragmentManager.FragmentLifecycleCallbacks() {
    override fun onFragmentPreAttached(manager: FragmentManager, fragment: Fragment, context: Context) {
        if (fragment.parentFragment !is NavHostFragment) return
        fragment.enterTransition = Fade(Fade.IN).apply { duration = 160 }
        fragment.reenterTransition = Fade(Fade.IN).apply { duration = 160 }
        fragment.exitTransition = Fade(Fade.OUT).apply { duration = 140 }
        fragment.returnTransition = Fade(Fade.OUT).apply { duration = 140 }
    }
}
