package com.system.location.service

import android.Manifest
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.navigation.findNavController
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.system.location.service.maps.MapProvider
import com.system.location.service.maps.mapProvider
import com.system.location.service.privacy.SdkPrivacy
import com.system.location.service.privacy.TencentPrivacy

/** ActivityScenario owns launch/recreation; the empty Compose rule only synchronizes semantics. */
internal fun ComposeTestRule.openPage(scenario: ActivityScenario<MainActivity>, destination: Int) {
    waitForIdle()
    scenario.onActivity { it.findNavController(R.id.nav_host_fragment_content_main).navigate(destination) }
    // Compose idleness alone does not await Fragment's seekable View transitions.
    waitUntil(5_000) {
        var ready = false
        scenario.onActivity { activity ->
            val host = activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main)
            ready = activity.findNavController(R.id.nav_host_fragment_content_main).currentDestination?.id == destination &&
                host?.childFragmentManager?.primaryNavigationFragment?.lifecycle?.currentState == androidx.lifecycle.Lifecycle.State.RESUMED
        }
        ready
    }
    waitForIdle()
    if (destination == R.id.nav_route_gallery) {
        waitUntil(10_000) {
            var ready = false
            scenario.onActivity { activity ->
                val host = activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main)
                val page = host?.childFragmentManager?.primaryNavigationFragment
                if (page != null) {
                    val model = androidx.lifecycle.ViewModelProvider(page)[com.system.location.service.ui.viewmodel.LibraryViewModel::class.java]
                    ready = !model.state.value.busy && model.state.value.items.size == when (model.state.value.kind) {
                        com.system.location.service.ui.viewmodel.LibraryKind.ROUTES -> com.system.location.service.data.repository.LibraryRepositories.routes.list().size
                        com.system.location.service.ui.viewmodel.LibraryKind.SCENARIOS -> com.system.location.service.data.repository.LibraryRepositories.scenarios.list().size
                        com.system.location.service.ui.viewmodel.LibraryKind.LOCATIONS -> com.system.location.service.data.repository.LibraryRepositories.locations.list().size
                    }
                }
            }
            ready
        }
        waitForIdle()
    }
}

/** Search the LazyColumn, including items that have not been composed yet. */
internal fun ComposeTestRule.scrollTo(matcher: SemanticsMatcher): SemanticsNodeInteraction {
    waitUntil(5_000) {
        runCatching { onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)
    }
    onNode(hasScrollToNodeAction()).performScrollToNode(matcher)
    return onNode(matcher)
}

internal fun ComposeTestRule.scrollToText(text: String, substring: Boolean = false) =
    scrollTo(hasText(text, substring = substring))

/** Explicit fixture consent follows the saved provider, including the fresh-install default. */
internal fun acceptSelectedMapForUiTest() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    instrumentation.runOnMainSync {
        when (context.mapProvider) {
            MapProvider.TENCENT -> TencentPrivacy.accept(context)
            MapProvider.AMAP -> SdkPrivacy.acceptMaps()
        }
    }
}

internal fun grantUiPermissions() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.POST_NOTIFICATIONS).forEach {
        if (androidx.core.content.ContextCompat.checkSelfPermission(instrumentation.targetContext, it) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, it)
        }
    }
}

/** SDK surfaces intentionally remain Views, and no longer have XML layout IDs in Compose. */
internal inline fun <reified T : View> View.descendantsOfType(): List<T> {
    val pending = ArrayDeque<View>()
    val found = mutableListOf<T>()
    pending.add(this)
    while (pending.isNotEmpty()) {
        val next = pending.removeFirst()
        if (next is T) found.add(next)
        if (next is ViewGroup) for (index in 0 until next.childCount) pending.add(next.getChildAt(index))
    }
    return found
}
