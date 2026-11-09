package com.system.location.service.maps

import android.content.Context
import com.system.location.service.ext.sharedPrefs

enum class MapProvider(val label: String) {
    AMAP("高德地图"), TENCENT("腾讯地图");
    companion object {
        fun restore(value: String?) = entries.firstOrNull { it.name == value } ?: AMAP
    }
}

var Context.mapProvider: MapProvider
    get() = MapProvider.restore(sharedPrefs.getString("map_provider", null))
    set(value) {
        check(sharedPrefs.edit().putString("map_provider", value.name).commit()) { "地图设置保存失败" }
    }

/** Logical styles deliberately do not persist SDK integer constants across providers. */
var Context.satelliteMap: Boolean
    get() = sharedPrefs.getBoolean("map_satellite", sharedPrefs.getInt("mapType", 1) == 2)
    set(value) { sharedPrefs.edit().putBoolean("map_satellite", value).apply() }
