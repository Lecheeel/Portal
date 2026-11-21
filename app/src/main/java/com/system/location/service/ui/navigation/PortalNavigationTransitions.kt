package com.system.location.service.ui.navigation

import android.content.Context
import android.view.Gravity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.navigation.fragment.NavHostFragment
import androidx.transition.Fade
import androidx.transition.Slide
import android.view.View
import android.os.Bundle
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import android.widget.FrameLayout
import com.system.location.service.ui.home.HomeComposeFragment

/** Seekable transitions reveal the previous page while Back is dragged or cancelled. */
internal class PortalNavigationTransitions : FragmentManager.FragmentLifecycleCallbacks() {
    override fun onFragmentViewCreated(manager: FragmentManager, fragment: Fragment, view: View, state: Bundle?) {
        if (fragment.parentFragment !is NavHostFragment || fragment is HomeComposeFragment) return
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.updateLayoutParams<FrameLayout.LayoutParams> {
                leftMargin = safe.left
                rightMargin = safe.right
                topMargin = safe.top + (64 * view.resources.displayMetrics.density).toInt()
                bottomMargin = safe.bottom
            }
            insets
        }
        view.post { ViewCompat.requestApplyInsets(view) }
    }

    override fun onFragmentPreAttached(manager: FragmentManager, fragment: Fragment, context: Context) {
        if (fragment.parentFragment !is NavHostFragment) return
        fragment.enterTransition = Slide(Gravity.END).apply { duration = 280 }
        fragment.reenterTransition = Fade(Fade.IN).apply { duration = 280 }
        fragment.exitTransition = Fade(Fade.OUT).apply { duration = 280 }
        fragment.returnTransition = Slide(Gravity.END).apply { duration = 280 }
    }
}
