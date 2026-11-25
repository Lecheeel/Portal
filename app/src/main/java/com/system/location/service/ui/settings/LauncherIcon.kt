package com.system.location.service.ui.settings

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.system.location.service.R

internal enum class LauncherIcon(val label: String, val alias: String, val preview: Int, val background: Int) {
    STANDARD("F4 · 默认", "StandardIcon", R.drawable.portal_icon_f4, R.color.portal_icon_white_background),
    A("A · 几何侧影", "AIcon", R.drawable.portal_icon_a, R.color.portal_icon_white_background),
    B("B · 上扬侧影", "BIcon", R.drawable.portal_icon_b, R.color.portal_icon_white_background),
    C("C · 斜视机身", "CIcon", R.drawable.portal_icon_c, R.color.portal_icon_white_background),
    E("E · 传送符号", "EIcon", R.drawable.portal_icon_e, R.color.portal_icon_white_background),
    F("F · 端口特写", "FIcon", R.drawable.portal_icon_f, R.color.portal_icon_white_background),
    E5("E5 · 放大侧影", "E5Icon", R.drawable.portal_icon_e5, R.color.portal_icon_white_background),
    E6("E6 · 斜向侧影", "E6Icon", R.drawable.portal_icon_e6, R.color.portal_icon_white_background),
    F5("F5 · 低角斜视", "F5Icon", R.drawable.portal_icon_f5, R.color.portal_icon_white_background),
    G4("G4 · 主端口特写", "G4Icon", R.drawable.portal_icon_g4, R.color.portal_icon_white_background),
    I1("I1 · 一体斜柄", "I1Icon", R.drawable.portal_icon_i1, R.color.portal_icon_white_background),
    I2("I2 · 一环两点", "I2Icon", R.drawable.portal_icon_i2, R.color.portal_icon_white_background),
    CLASSIC("经典版", "ClassicIcon", R.drawable.portal_icon_standard, R.color.portal_icon_standard_background),
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
                // Commit launcher aliases together so there is exactly one enabled entry.
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
