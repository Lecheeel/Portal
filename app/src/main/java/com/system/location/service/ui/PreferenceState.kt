package com.system.location.service.ui

import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.system.location.service.ext.sharedPrefs
import kotlinx.coroutines.awaitCancellation

/** Invalidate preference snapshots while visible, and re-read changes made while the page was stopped. */
@Composable
internal fun preferenceRevision(): Int {
    val preferences = LocalContext.current.sharedPrefs
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val revision by produceState(0, preferences, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> value += 1 }
            preferences.registerOnSharedPreferenceChangeListener(listener)
            value += 1
            try { awaitCancellation() }
            finally { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
        }
    }
    return revision
}
