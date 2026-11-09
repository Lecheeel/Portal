package com.system.location.service.designsystem

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.system.location.service.designsystem.components.PortalCard
import com.system.location.service.designsystem.components.PortalSectionTitle
import com.system.location.service.designsystem.theme.PortalTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PortalComponentsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun cardSectionAndContentAreRendered() {
        compose.setContent {
            PortalTheme {
                PortalCard {
                    PortalSectionTitle("运行中心", "状态与诊断")
                    Text("尚未开始")
                }
            }
        }

        compose.onNodeWithText("运行中心").assertIsDisplayed()
        compose.onNodeWithText("状态与诊断").assertIsDisplayed()
        compose.onNodeWithText("尚未开始").assertIsDisplayed()
    }
}
