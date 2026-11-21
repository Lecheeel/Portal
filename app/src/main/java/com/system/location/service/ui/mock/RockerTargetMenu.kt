package com.system.location.service.ui.mock

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.system.location.service.ui.PortalDialog
import com.system.location.service.ui.PortalDialogContent
import com.system.location.service.R
import com.system.location.service.core.scenario.Scenario
import com.system.location.service.data.repository.LibraryRepositories
import com.system.location.service.ext.movementProfile
import com.system.location.service.runtime.ScenarioRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** A real overlay window, so saved targets can also be selected above another app. */
internal class RockerTargetMenu(private val context: Context) {
    private data class Target(val id: String, val name: String, val route: Boolean)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loading: Job? = null
    private var dialog: PortalDialog? = null
    private var targets by mutableStateOf<List<Target>>(emptyList())
    private var message by mutableStateOf(context.getString(R.string.overlay_target_loading))
    private var starting = false

    fun open() {
        if (starting) return
        close()
        targets = emptyList()
        message = context.getString(R.string.overlay_target_loading)
        if (!present()) return
        loading = scope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    LibraryRepositories.ensureMigrated()
                    LibraryRepositories.routes.list()
                        .sortedWith(compareByDescending<com.system.location.service.core.repository.SavedRoute> { it.favorite }.thenBy { it.route.name })
                        .map { Target(it.route.id, it.route.name, true) } +
                        LibraryRepositories.locations.list().sortedWith(compareByDescending<com.system.location.service.core.repository.SavedLocation> { it.favorite }.thenBy { it.name })
                            .map { Target(it.id, it.name, false) }
                }
                targets = loaded
                message = if (loaded.isEmpty()) context.getString(R.string.overlay_target_empty) else ""
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                message = context.getString(R.string.overlay_target_load_failed)
            }
        }
    }

    private fun start(target: Target) {
        if (starting) return
        starting = true
        scope.launch {
            try {
                // Re-read the record: another page may have edited or deleted it while the menu was open.
                val scene = withContext(Dispatchers.IO) {
                    val profile = context.movementProfile()
                    if (target.route) LibraryRepositories.routes.get(target.id)?.let {
                        Scenario(it.route.id, it.route.name, route = it.route, mode = it.mode, profile = profile)
                    } else LibraryRepositories.locations.get(target.id)?.let {
                        Scenario(UUID.randomUUID().toString(), it.name, point = it.coordinate, profile = profile)
                    }
                }
                if (scene == null) {
                    Toast.makeText(context, R.string.overlay_target_missing, Toast.LENGTH_LONG).show()
                } else if (!ScenarioRuntime.start(scene).await()) {
                    val message = ScenarioRuntime.state.value.error?.let { "${it.reason}\n${it.suggestion}" }
                        ?: context.getString(R.string.overlay_target_start_failed)
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Toast.makeText(context, R.string.overlay_target_start_failed, Toast.LENGTH_LONG).show()
            } finally {
                starting = false
            }
        }
    }

    private fun present(): Boolean {
        val next = PortalDialog(context, overlay = true)
        next.content {
            PortalDialogContent(context.getString(R.string.overlay_target_menu), message.takeIf { it.isNotBlank() },
                body = {
                    targets.forEach { target ->
                        TextButton(onClick = { close(); start(target) }, modifier = Modifier.fillMaxWidth()) {
                            Text(context.getString(if (target.route) R.string.overlay_target_route
                                else R.string.overlay_target_location, target.name))
                        }
                    }
                }, buttons = {
                    TextButton(onClick = ::close) { Text(context.getString(android.R.string.cancel)) }
                })
        }
        next.setOnDismissListener {
            if (dialog === next) {
                loading?.cancel()
                loading = null
                dialog = null
            }
        }
        return try {
            next.show()
            dialog = next
            true
        } catch (_: RuntimeException) {
            next.dismiss()
            Toast.makeText(context, R.string.overlay_target_open_failed, Toast.LENGTH_LONG).show()
            false
        }
    }

    fun close() {
        loading?.cancel()
        loading = null
        dialog?.dismiss()
        dialog = null
    }
}
