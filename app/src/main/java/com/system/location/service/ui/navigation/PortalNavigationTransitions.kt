package com.system.location.service.ui.navigation

import android.content.Context
import android.view.Gravity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.navigation.fragment.NavHostFragment
import androidx.transition.Fade
import androidx.transition.Slide

/** Seekable transitions reveal the previous page while Back is dragged or cancelled. */
internal class PortalNavigationTransitions : FragmentManager.FragmentLifecycleCallbacks() {
    override fun onFragmentPreAttached(manager: FragmentManager, fragment: Fragment, context: Context) {
        if (fragment.parentFragment !is NavHostFragment) return
        fragment.enterTransition = Slide(Gravity.END).apply { duration = 280 }
        fragment.reenterTransition = Fade(Fade.IN).apply { duration = 280 }
        fragment.exitTransition = Fade(Fade.OUT).apply { duration = 280 }
        fragment.returnTransition = Slide(Gravity.END).apply { duration = 280 }
    }
}
