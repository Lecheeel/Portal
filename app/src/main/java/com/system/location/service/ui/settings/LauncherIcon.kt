package com.system.location.service.ui.settings

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.system.location.service.R

internal enum class LauncherIcon(val label: String, val alias: String, val preview: Int, val background: Int) {
    STANDARD("普通版", "StandardIcon", R.drawable.portal_icon_standard, R.color.portal_icon_standard_background),
    NEON("霓虹版", "NeonIcon", R.drawable.portal_icon_neon, R.color.portal_icon_neon_background);

    // Class names use the namespace; packageName can include a debug applicationId suffix.
    fun component(context: Context) = ComponentName(context.packageName,
        "com.system.location.service.launcher.$alias")

    companion object {
        fun current(context: Context): LauncherIcon = entries.firstOrNull {
            val state = context.packageManager.getComponentEnabledSetting(it.component(context))
            state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
                (it == STANDARD && state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
        } ?: STANDARD

        fun select(context: Context, selected: LauncherIcon) {
            val manager = context.packageManager
            fun state(icon: LauncherIcon) = if (icon == selected) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            if (Build.VERSION.SDK_INT >= 33) {
                // Commit both aliases together so launchers never see two icons or no icon.
                manager.setComponentEnabledSettings(entries.map {
                    PackageManager.ComponentEnabledSetting(it.component(context), state(it), PackageManager.DONT_KILL_APP)
                })
            } else {
                // Older Android: enable the new entry before removing the previous one.
                manager.setComponentEnabledSetting(selected.component(context), state(selected), PackageManager.DONT_KILL_APP)
                entries.filter { it != selected }.forEach {
                    manager.setComponentEnabledSetting(it.component(context), state(it), PackageManager.DONT_KILL_APP)
                }
            }
        }
    }
}
