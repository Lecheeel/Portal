package com.system.location.service.ui.mock

import android.content.Context
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
    private var dialog: AlertDialog? = null
    private var starting = false

    fun open() {
        if (starting) return
        close()
        if (!present(MaterialAlertDialogBuilder(context)
                .setTitle(R.string.overlay_target_menu)
                .setMessage(R.string.overlay_target_loading)
                .setNegativeButton(android.R.string.cancel) { _, _ -> loading?.cancel() })) return
        loading = scope.launch {
            try {
                val targets = withContext(Dispatchers.IO) {
                    LibraryRepositories.ensureMigrated()
                    LibraryRepositories.routes.list()
                        .sortedWith(compareByDescending<com.system.location.service.core.repository.SavedRoute> { it.favorite }.thenBy { it.route.name })
                        .map { Target(it.route.id, it.route.name, true) } +
                        LibraryRepositories.locations.list().sortedWith(compareByDescending<com.system.location.service.core.repository.SavedLocation> { it.favorite }.thenBy { it.name })
                            .map { Target(it.id, it.name, false) }
                }
                val builder = MaterialAlertDialogBuilder(context).setTitle(R.string.overlay_target_menu)
                    .setNegativeButton(android.R.string.cancel, null)
                if (targets.isEmpty()) builder.setMessage(R.string.overlay_target_empty)
                else builder.setItems(targets.map { target ->
                    context.getString(if (target.route) R.string.overlay_target_route else R.string.overlay_target_location, target.name)
                }.toTypedArray()) { _, index -> start(targets[index]) }
                present(builder)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                present(MaterialAlertDialogBuilder(context).setTitle(R.string.overlay_target_menu)
                    .setMessage(R.string.overlay_target_load_failed).setPositiveButton(android.R.string.ok, null))
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

    private fun present(builder: MaterialAlertDialogBuilder): Boolean {
        dialog?.dismiss()
        val next = builder.create()
        next.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        next.setOnCancelListener { loading?.cancel() }
        return try {
            next.show()
            dialog = next
            true
        } catch (_: RuntimeException) {
            next.dismiss()
            dialog = null
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
