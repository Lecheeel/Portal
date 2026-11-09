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

/** ActivityScenario owns launch/recreation; the empty Compose rule only synchronizes semantics. */
internal fun ComposeTestRule.openPage(scenario: ActivityScenario<MainActivity>, destination: Int) {
    waitForIdle()
    scenario.onActivity { it.findNavController(R.id.nav_host_fragment_content_main).navigate(destination) }
    waitForIdle()
}

/** Search the LazyColumn, including items that have not been composed yet. */
internal fun ComposeTestRule.scrollTo(matcher: SemanticsMatcher): SemanticsNodeInteraction {
    onNode(hasScrollToNodeAction()).performScrollToNode(matcher)
    return onNode(matcher)
}

internal fun ComposeTestRule.scrollToText(text: String, substring: Boolean = false) =
    scrollTo(hasText(text, substring = substring))

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
