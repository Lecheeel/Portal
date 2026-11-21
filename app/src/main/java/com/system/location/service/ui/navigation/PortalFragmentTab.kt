package com.system.location.service.ui.navigation

import android.os.Bundle
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.fragment.app.Fragment
import androidx.fragment.compose.AndroidFragment
import androidx.lifecycle.Lifecycle

/** Keeps tab ViewModels alive while Compose owns layout and AndroidFragment restores containers. */
@Composable
internal inline fun <reified T : Fragment> PortalFragmentTab(
    visible: Boolean,
    modifier: Modifier = Modifier,
    arguments: Bundle = Bundle.EMPTY,
) {
    var fragment by remember { mutableStateOf<T?>(null) }
    AndroidFragment<T>(modifier.alpha(if (visible) 1f else 0f), arguments = arguments) { fragment = it }
    LaunchedEffect(fragment, visible) {
        fragment?.let { page ->
            val manager = page.parentFragmentManager
            val state = if (visible) Lifecycle.State.RESUMED else Lifecycle.State.CREATED
            if (!manager.isStateSaved) {
                manager.beginTransaction().apply {
                    if (visible) { show(page); setPrimaryNavigationFragment(page) }
                    else {
                        hide(page)
                        if (manager.primaryNavigationFragment === page) setPrimaryNavigationFragment(null)
                    }
                    setMaxLifecycle(page, state)
                }.commitNow()
            }
        }
    }
}
