package com.system.location.service

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.net.wifi.WifiManager
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.system.location.service.backend.mock.AndroidMockProviderPort
import com.system.location.service.core.backend.BackendType
import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.scenario.Scenario
import com.system.location.service.runtime.ScenarioRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MockWirelessReminderUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun showsOnlyForActiveRadiosAndIgnoreLastsForTheCurrentStart() {
        assumeTrue("Run on an isolated emulator with the mock AppOp granted",
            InstrumentationRegistry.getArguments().getString("mockWirelessLab") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(AndroidMockProviderPort(context).permissionGranted())
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val bluetooth = context.getSystemService(BluetoothManager::class.java)?.adapter
        val originalWifi = wifi.isWifiEnabled
        val originalBluetooth = bluetooth?.isEnabled == true
        val title = context.getString(R.string.mock_wireless_title)
        val wifiSettings = context.getString(R.string.mock_wireless_wifi_settings)
        val bluetoothSettings = context.getString(R.string.mock_wireless_bluetooth_settings)
        val ignore = context.getString(R.string.mock_wireless_ignore)
        fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
        fun setRadios(wifiEnabled: Boolean, bluetoothEnabled: Boolean) {
            shell("svc wifi ${if (wifiEnabled) "enable" else "disable"}")
            if (bluetooth != null) {
                check(shell("su 0 id").contains("uid=0")) { "The emulator must permit root for Bluetooth lab setup" }
                shell("su 0 svc bluetooth ${if (bluetoothEnabled) "enable" else "disable"}")
            }
            compose.waitUntil(15_000) {
                wifi.wifiState == if (wifiEnabled) WifiManager.WIFI_STATE_ENABLED else WifiManager.WIFI_STATE_DISABLED
            }
            if (bluetooth != null) compose.waitUntil(15_000) {
                bluetooth.state == if (bluetoothEnabled) BluetoothAdapter.STATE_ON else BluetoothAdapter.STATE_OFF
            }
        }
        fun start(id: String) = runBlocking {
            assertTrue(ScenarioRuntime.start(Scenario(id, "无线提醒测试", point = Wgs84(31.23, 121.47))).await())
        }
        fun awaitDialog() = compose.waitUntil(10_000) {
            compose.onAllNodesWithText(title).fetchSemanticsNodes().size == 1
        }
        runBlocking {
            ScenarioRuntime.awaitInitialized()
            assertFalse(ScenarioRuntime.state.value.isActive)
            assertTrue(ScenarioRuntime.selectBackend(BackendType.MOCK_PROVIDER).await())
        }
        acceptSelectedMapForUiTest()
        grantUiPermissions()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                setRadios(wifiEnabled = true, bluetoothEnabled = false)
                start("wireless-wifi")
                awaitDialog()
                compose.onNodeWithText(wifiSettings).assertExists()
                compose.onNodeWithText(bluetoothSettings).assertDoesNotExist()
                compose.onNodeWithText(ignore).performClick()
                activity.recreate()
                compose.onNodeWithText(title).assertDoesNotExist()
                assertTrue(ScenarioRuntime.state.value.isActive)
                runBlocking { assertTrue(ScenarioRuntime.stop().await()) }

                setRadios(wifiEnabled = false, bluetoothEnabled = false)
                start("wireless-off")
                compose.waitUntil(10_000) { ScenarioRuntime.mockWirelessReminder.value == null }
                compose.onNodeWithText(title).assertDoesNotExist()
                runBlocking { assertTrue(ScenarioRuntime.stop().await()) }

                if (bluetooth != null) {
                    setRadios(wifiEnabled = false, bluetoothEnabled = true)
                    start("wireless-bluetooth")
                    awaitDialog()
                    compose.onNodeWithText(bluetoothSettings).assertExists()
                    compose.onNodeWithText(wifiSettings).assertDoesNotExist()
                    compose.onNodeWithText(bluetoothSettings).performClick()
                    compose.waitUntil(10_000) {
                        shell("dumpsys activity activities").lineSequence().any {
                            "ResumedActivity" in it && "com.android.settings/" in it
                        }
                    }
                    setRadios(wifiEnabled = false, bluetoothEnabled = false)
                    shell("input keyevent KEYCODE_BACK")
                    compose.waitUntil(10_000) { ScenarioRuntime.mockWirelessReminder.value == null }
                    compose.onNodeWithText(title).assertDoesNotExist()
                }
            }
        } finally {
            runBlocking { ScenarioRuntime.stop().await() }
            setRadios(originalWifi, originalBluetooth)
        }
    }
}
