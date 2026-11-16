package com.system.location.service

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.system.location.service.core.carrier.CarrierIdentityConfig
import com.system.location.service.core.carrier.SimCarrierProfile
import com.system.location.service.debug.CarrierEditorTestActivity
import com.system.location.service.ext.sharedPrefs
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CarrierEditorRecoveryUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test(timeout = 120_000)
    fun dualSimDraftsAndAppSelectionsSurviveNavigationAndRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.sharedPrefs
        val keys = listOf("carrierIdentityEnabled", "carrierIdentityJson")
        val snapshot = preferences.all
        val config = CarrierIdentityConfig(sims = listOf(
            SimCarrierProfile(1, countryIso = "US", mcc = 310, mnc = 260, carrierName = "Saved one"),
            SimCarrierProfile(2, countryIso = "CN", mcc = 460, mnc = 0, mncDigits = 2, carrierName = "Saved two"),
        ))
        try {
            assertTrue(preferences.edit().putBoolean("carrierIdentityEnabled", false)
                .putString("carrierIdentityJson", Json.encodeToString(CarrierIdentityConfig.serializer(), config)).commit())
            ActivityScenario.launch(CarrierEditorTestActivity::class.java).use { scenario ->
                compose.scrollToText("运营商名称").performTextReplacement("Unsaved one")
                compose.scrollToText("仅分应用").performClick()
                compose.scrollToText("SIM 2").performClick()
                compose.scrollToText("运营商名称").performTextReplacement("Unsaved two")
                scenario.onActivity { activity ->
                    activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                        .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
                }
                compose.scrollToText("添加应用").performClick()
                compose.onNodeWithText("搜索应用名称或包名").performTextInput(context.packageName)
                scenario.onActivity { activity ->
                    activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                        .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
                }
                compose.onNode(hasText(context.packageName) and isToggleable()).performClick()
                compose.onNodeWithText("保存选择（1）").performClick()
                compose.scrollToText("运营商名称")
                compose.onNode(hasSetTextAction() and hasText("Unsaved two")).assertIsDisplayed()
                scenario.recreate()
                compose.scrollToText("仅分应用").assertIsSelected()
                compose.scrollToText("运营商名称")
                compose.onNode(hasSetTextAction() and hasText("Unsaved two")).assertIsDisplayed()
                compose.scrollToText("SIM 1").performClick()
                compose.scrollToText("运营商名称")
                compose.onNode(hasSetTextAction() and hasText("Unsaved one")).assertIsDisplayed()
                compose.scrollToText(context.packageName).assertIsDisplayed()
            }
        } finally {
            val edit = preferences.edit()
            keys.forEach { key -> when (val value = snapshot[key]) {
                is String -> edit.putString(key, value)
                is Boolean -> edit.putBoolean(key, value)
                else -> edit.remove(key)
            } }
            check(edit.commit())
        }
    }
}
