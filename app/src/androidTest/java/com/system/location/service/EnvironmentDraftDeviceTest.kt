package com.system.location.service

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.system.location.service.core.environment.*
import com.system.location.service.environment.EnvironmentStore
import com.system.location.service.ui.environment.EnvironmentEditorViewModel
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class EnvironmentDraftDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun largeDraftRestoresFromCacheWithoutPuttingThePayloadInSavedState() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val handle = SavedStateHandle()
        lateinit var first: EnvironmentEditorViewModel
        var second: EnvironmentEditorViewModel? = null
        instrumentation.runOnMainSync { first = EnvironmentEditorViewModel(app, handle) }
        val drafts = EnvironmentStore(File(app.cacheDir, "environment-editor-drafts"))
        val key = handle.get<String>("draftName")!!
        try {
            compose.waitUntil(5_000) { first.state.value.profile != null }
            val profile = first.state.value.profile!!.copy(wifi = listOf(
                WifiAp("large-draft", "A4:50:46:12:34:56", -60, 2412, 1, capabilities = "x".repeat(700_000)),
            ))
            instrumentation.runOnMainSync { first.update(profile) }
            compose.waitUntil(10_000) { key in drafts.list() && drafts.load(key) == profile }
            val snapshot = handle.keys().associateWith { handle.get<Any>(it) }
            assertTrue(snapshot.values.filterIsInstance<String>().all { it.length < 100 })
            instrumentation.runOnMainSync { second = EnvironmentEditorViewModel(app, SavedStateHandle(snapshot)) }
            compose.waitUntil(5_000) { second!!.state.value.profile != null }
            assertEquals(profile, second!!.state.value.profile)
        } finally {
            instrumentation.runOnMainSync { first.viewModelScope.cancel(); second?.viewModelScope?.cancel() }
            drafts.delete(key)
        }
    }
}
