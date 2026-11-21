package com.system.location.service

import android.annotation.SuppressLint
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.transition.Fade
import androidx.transition.TransitionSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Fragment completion callbacks may remove a sibling while a nested transition is resumed. */
@SuppressLint("RestrictedApi")
@RunWith(AndroidJUnit4::class)
class TransitionReentrancyDeviceTest {
    @Test fun nestedResumeSurvivesSiblingRemovalDuringChildCallback() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val root = FrameLayout(instrumentation.targetContext)
            val children = TransitionSet()
            var lastResumed = false
            val last = object : Fade() {
                override fun resume(sceneRoot: View?) { lastResumed = true; super.resume(sceneRoot) }
            }
            children.addTransition(object : Fade() {
                override fun resume(sceneRoot: View?) {
                    children.removeTransition(last)
                    super.resume(sceneRoot)
                }
            }).addTransition(last)
            TransitionSet().addTransition(children).resume(root)
            assertEquals(1, children.transitionCount)
            assertTrue("Every child in the original operation must resume", lastResumed)
        }
    }
}
