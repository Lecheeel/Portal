package com.system.location.service.ui

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.system.location.service.designsystem.theme.PortalTheme

/** A platform window with Compose content, also usable above other apps without an Activity token. */
internal class PortalDialog(context: Context, overlay: Boolean = false) : ComponentDialog(context) {
    private val activityOwner = generateSequence(context) { (it as? ContextWrapper)?.baseContext }
        .filterIsInstance<android.app.Activity>().firstOrNull() as? LifecycleOwner
    private val observer = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_DESTROY) dismiss()
    }

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        if (overlay) window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
    }

    fun content(block: @Composable () -> Unit): PortalDialog = apply {
        setContentView(ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { PortalTheme { block() } }
        })
    }

    override fun onStart() {
        super.onStart()
        activityOwner?.lifecycle?.addObserver(observer)
        val density = context.resources.displayMetrics.density
        val width = minOf(context.resources.displayMetrics.widthPixels - (48 * density).toInt(), (560 * density).toInt())
        window?.setLayout(width.coerceAtLeast(1), WindowManager.LayoutParams.WRAP_CONTENT)
    }

    override fun onStop() {
        activityOwner?.lifecycle?.removeObserver(observer)
        super.onStop()
    }
}

internal fun showPortalChoiceDialog(context: Context, title: String, choices: List<String>,
    selected: Int? = null, onSelect: (Int) -> Unit): PortalDialog {
    val dialog = PortalDialog(context)
    dialog.content {
        PortalDialogContent(title, body = {
            choices.forEachIndexed { index, label ->
                Row(Modifier.fillMaxWidth().selectable(selected == index, role = Role.RadioButton,
                    onClick = { dialog.dismiss(); onSelect(index) }).padding(vertical = 12.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    if (selected != null) {
                        RadioButton(selected == index, onClick = null)
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }, buttons = {
            TextButton(onClick = { dialog.dismiss() }) { Text(context.getString(android.R.string.cancel)) }
        })
    }
    dialog.show()
    return dialog
}

@Composable
internal fun PortalDialogContent(title: String, message: String? = null,
    body: (@Composable ColumnScope.() -> Unit)? = null,
    buttons: @Composable FlowRowScope.() -> Unit) {
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(24.dp).heightIn(max = 560.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(top = 16.dp)) {
                message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                body?.invoke(this)
            }
            FlowRow(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End,
                content = buttons)
        }
    }
}
