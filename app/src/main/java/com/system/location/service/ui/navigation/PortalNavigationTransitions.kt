package com.system.location.service.ui.navigation

import android.content.Context
import android.view.Gravity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.navigation.fragment.NavHostFragment
import androidx.transition.Fade
import androidx.transition.Slide
import android.view.View
import android.view.ViewGroup
import com.system.location.service.maps.PortalMapView
import java.util.concurrent.TimeUnit

/** Seekable transitions reveal the previous page while Back is dragged or cancelled. */
internal class PortalNavigationTransitions : FragmentManager.FragmentLifecycleCallbacks() {
    override fun onFragmentPreAttached(manager: FragmentManager, fragment: Fragment, context: Context) {
        if (fragment.parentFragment !is NavHostFragment) return
        fragment.enterTransition = Slide(Gravity.END).apply { duration = 280 }
        fragment.reenterTransition = Fade(Fade.IN).apply { duration = 280 }
        fragment.exitTransition = Fade(Fade.OUT).apply { duration = 280 }
        fragment.returnTransition = Slide(Gravity.END).apply { duration = 280 }
        // Preserve a real GL frame before the outgoing map surface is detached. A SurfaceView
        // cannot itself participate in an alpha transition and otherwise reveals black.
        val map = manager.fragments.asSequence().filter { it !== fragment }
            .mapNotNull { it.view }.flatMap(::maps).firstOrNull { it.isShown }
        if (map != null) {
            fragment.postponeEnterTransition(250, TimeUnit.MILLISECONDS)
            map.holdTransitionFrame { fragment.startPostponedEnterTransition() }
        }
    }

    private fun maps(view: View): Sequence<PortalMapView> = when (view) {
        is PortalMapView -> sequenceOf(view)
        is ViewGroup -> (0 until view.childCount).asSequence().flatMap { maps(view.getChildAt(it)) }
        else -> emptySequence()
    }
}
