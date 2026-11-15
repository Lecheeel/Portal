package com.system.location.service.ui.environment

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.system.location.service.core.environment.EnvironmentProfile
import com.system.location.service.core.environment.EnvironmentSourceType
import com.system.location.service.core.geo.Wgs84
import com.system.location.service.environment.EnvironmentStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

internal data class EnvironmentEditorState(
    val profile: EnvironmentProfile? = null,
    val busy: Boolean = true,
    val error: String? = null,
    val saved: Boolean = false,
)

/** The draft survives navigation and recreation; files are touched only on explicit save. */
internal class EnvironmentEditorViewModel(
    application: Application,
    handle: SavedStateHandle,
) : AndroidViewModel(application) {
    val originalName: String = handle["name"] ?: ""
    private val draftName = handle.get<String>("draftName") ?: UUID.randomUUID().toString().also { handle["draftName"] = it }
    private val drafts = EnvironmentStore(File(application.cacheDir, "environment-editor-drafts"))
    private val changes = Channel<EnvironmentProfile>(Channel.CONFLATED)
    private val mutable = MutableStateFlow(EnvironmentEditorState())
    val state = mutable.asStateFlow()

    init {
        // An imported profile can reach 2 MiB. Save only its cache key in the Android state
        // bundle, otherwise restoring a large draft can fail with TransactionTooLargeException.
        viewModelScope.launch {
            for (profile in changes) {
                try { withContext(Dispatchers.IO) { drafts.save(draftName, profile) } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    mutable.value = state.value.copy(error = "草稿暂存失败：${error.message}，请保存环境后再退出")
                }
            }
        }
        load()
    }

    fun load() {
        if (state.value.profile != null) return
        viewModelScope.launch {
            mutable.value = EnvironmentEditorState()
            try {
                val profile = withContext(Dispatchers.IO) {
                    draftName.takeIf { it in drafts.list() }?.let(drafts::load)
                        ?: if (originalName.isNotBlank()) EnvironmentStore(getApplication<Application>()).load(originalName)
                        else EnvironmentProfile(source = EnvironmentSourceType.REPLAY,
                            generatedAtMillis = System.currentTimeMillis(), seed = System.currentTimeMillis())
                }
                mutable.value = EnvironmentEditorState(profile, busy = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                mutable.value = EnvironmentEditorState(busy = false, error = "读取失败：${error.message}")
            }
        }
    }

    fun update(profile: EnvironmentProfile) {
        mutable.value = state.value.copy(profile = profile, error = null)
        changes.trySend(profile)
    }

    fun save(name: String, latitude: String, longitude: String, accuracy: String) {
        val current = state.value
        val profile = current.profile ?: return
        if (current.busy || current.saved) return
        viewModelScope.launch {
            mutable.value = current.copy(busy = true, error = null)
            try {
                require(name.isNotBlank()) { "请输入环境名称" }
                val hasOrigin = latitude.isNotBlank() || longitude.isNotBlank()
                val origin = if (hasOrigin) Wgs84(latitude.trim().toDouble(), longitude.trim().toDouble()) else null
                require(origin != null || accuracy.isBlank()) { "请先填写环境坐标" }
                val next = profile.copy(source = EnvironmentSourceType.REPLAY, origin = origin,
                    originAccuracyMeters = accuracy.trim().takeIf { it.isNotEmpty() }?.toDouble())
                withContext(Dispatchers.IO) {
                    val store = EnvironmentStore(getApplication<Application>())
                    if (originalName.isBlank()) store.saveNew(name.trim(), next)
                    else store.save(originalName, next)
                }
                mutable.value = current.copy(profile = next, busy = false, saved = true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                mutable.value = current.copy(busy = false, error = "保存失败：${error.message ?: "请检查输入"}")
            }
        }
    }

    override fun onCleared() {
        changes.close()
        super.onCleared()
    }
}
