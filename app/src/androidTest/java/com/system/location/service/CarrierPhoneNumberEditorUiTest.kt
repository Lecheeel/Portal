package com.system.location.service

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.system.location.service.core.carrier.*
import com.system.location.service.debug.CarrierEditorTestActivity
import com.system.location.service.ext.sharedPrefs
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CarrierPhoneNumberEditorUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun text(value: String): SemanticsNodeInteraction {
        compose.onNode(hasScrollToIndexAction() and SemanticsMatcher.keyIsDefined(
            androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(hasText(value))
        return compose.onNodeWithText(value)
    }
    private fun number() = compose.onNodeWithText("模拟手机号码（可选）").fetchSemanticsNode()
        .config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text

    @Test(timeout = 120_000) fun citySelectionRandomFillTwoSlotsAndRecreationPersistNumbers() {
        val prefs = InstrumentationRegistry.getInstrumentation().targetContext.sharedPrefs
        val saved = prefs.all
        val config = CarrierIdentityConfig(sims = listOf(
            SimCarrierProfile(1, countryIso = "CN", mcc = 460, mnc = 0, mncDigits = 2, carrierName = "CMCC"),
            SimCarrierProfile(2, countryIso = "AU", mcc = 505, mnc = 1, mncDigits = 2, carrierName = "Telstra")))
        try {
            check(prefs.edit().putBoolean("carrierIdentityEnabled", false)
                .putString("carrierIdentityJson", CarrierIdentityCodec.encode(config)).commit())
            ActivityScenario.launch(CarrierEditorTestActivity::class.java).use { activity ->
                text("选择号段归属城市")
                compose.waitUntil(10000) {
                    !compose.onNodeWithText("选择号段归属城市").fetchSemanticsNode().config
                        .contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
                }
                compose.onNodeWithText("选择号段归属城市").performClick()
                compose.onNodeWithText("搜索省、市或地区").performTextInput("北京")
                compose.onNodeWithText("北京市").performClick()
                text("随机填充号码").performClick()
                var cn = number()
                assertTrue(CarrierPhoneNumbers.belongsToCity(cn, CarrierPhoneCity("北京市", "CMCC")))
                activity.recreate()
                text("模拟手机号码（可选）")
                assertEquals(cn, number())
                text("MNC").performTextReplacement("01")
                text("模拟手机号码（可选）")
                assertEquals("", number())
                text("选择号段归属城市").assertIsDisplayed()
                text("MNC").performTextReplacement("00")
                text("选择号段归属城市")
                compose.waitUntil(10000) {
                    !compose.onNodeWithText("选择号段归属城市").fetchSemanticsNode().config
                        .contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
                }
                compose.onNodeWithText("选择号段归属城市").performClick()
                compose.onNodeWithText("北京市").performClick()
                text("随机填充号码").performClick()
                cn = number()
                assertTrue(CarrierPhoneNumbers.belongsToCity(cn, CarrierPhoneCity("北京市", "CMCC")))
                text("SIM 2").performClick()
                text("随机填充号码").performClick()
                val au = number()
                assertTrue(CarrierPhoneNumbers.matchesRegion(au, "AU"))
                text("保存并应用").performClick()
                compose.waitUntil(10000) {
                    CarrierIdentityCodec.decode(prefs.getString("carrierIdentityJson", "")!!).sims
                        .firstOrNull { it.slot == 2 }?.phoneNumber == au
                }
                val persisted = CarrierIdentityCodec.decode(prefs.getString("carrierIdentityJson", "")!!)
                assertEquals(cn, persisted.sims.first { it.slot == 1 }.phoneNumber)
                assertEquals("北京市", persisted.sims.first { it.slot == 1 }.phoneNumberCity)
                assertEquals(au, persisted.sims.first { it.slot == 2 }.phoneNumber)
            }
        } finally {
            val edit = prefs.edit()
            for (key in listOf("carrierIdentityEnabled", "carrierIdentityJson")) when (val value = saved[key]) {
                is Boolean -> edit.putBoolean(key, value)
                is String -> edit.putString(key, value)
                else -> edit.remove(key)
            }
            check(edit.commit())
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            com.system.location.service.service.MockServiceHelper.putConfig(
                context.getSystemService(android.location.LocationManager::class.java), context)
        }
    }
}
