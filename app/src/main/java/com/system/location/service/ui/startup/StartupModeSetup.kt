package com.system.location.service.ui.startup

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.system.location.service.core.backend.BackendType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun StartupModeSetup(activity: Activity, onBack: () -> Unit, onComplete: suspend (BackendType) -> Boolean) {
    var selected by rememberSaveable { mutableStateOf(BackendType.MOCK_PROVIDER) }
    var requirement by remember(selected) { mutableStateOf(ModeRequirement.CHECKING) }
    var refresh by remember { mutableIntStateOf(0) }
    var working by remember { mutableStateOf(false) }
    var rootResult by remember { mutableStateOf(ModeRequirement.ROOT) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()

    suspend fun check(mode: BackendType, verifyRoot: Boolean): ModeRequirement = try {
        checkStartupMode(activity.applicationContext, mode, verifyRoot)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        ModeRequirement.CHECK_FAILED
    }

    LaunchedEffect(selected, refresh, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (!working) {
                requirement = ModeRequirement.CHECKING
                val result = check(selected, verifyRoot = false)
                if (!working) requirement = if (result == ModeRequirement.ROOT) rootResult else result
            }
        }
    }
    BackHandler { if (!working) onBack() }

    StartupModeScreen(selected = selected, requirement = requirement, working = working, onBack = onBack,
        onSelect = { mode -> if (!working && selected != mode) selected = mode }, onAction = {
            if (working) return@StartupModeScreen
            when (requirement) {
                ModeRequirement.DEVELOPER_OPTIONS, ModeRequirement.MOCK -> {
                    val action = if (requirement == ModeRequirement.DEVELOPER_OPTIONS) Settings.ACTION_DEVICE_INFO_SETTINGS
                        else Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
                    val opened = listOf(action, Settings.ACTION_SETTINGS).any {
                        runCatching { activity.startActivity(Intent(it)) }.isSuccess
                    }
                    if (!opened) {
                        requirement = ModeRequirement.CHECK_FAILED
                    }
                }
                ModeRequirement.ROOT, ModeRequirement.ROOT_DENIED, ModeRequirement.READY, ModeRequirement.SAVE_FAILED -> {
                    val completing = requirement == ModeRequirement.READY || requirement == ModeRequirement.SAVE_FAILED
                    working = true
                    val mode = selected
                    scope.launch {
                        try {
                            val result = check(mode, verifyRoot = mode == BackendType.NATIVE)
                            if (mode == BackendType.NATIVE) {
                                rootResult = when (result) {
                                    ModeRequirement.READY, ModeRequirement.ROOT_DENIED -> result
                                    else -> ModeRequirement.ROOT
                                }
                            }
                            requirement = if (completing && result == ModeRequirement.READY && !onComplete(mode))
                                ModeRequirement.SAVE_FAILED else result
                        } finally {
                            working = false
                        }
                    }
                }
                ModeRequirement.MODULE, ModeRequirement.CHECK_FAILED -> refresh++
                ModeRequirement.CHECKING, ModeRequirement.ANDROID, ModeRequirement.ABI, ModeRequirement.NATIVE_LIBRARY -> Unit
            }
        })
}
