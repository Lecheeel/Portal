package com.system.location.service.update

import android.content.Context
import android.content.SharedPreferences

/** The stable channel is the safe default, including when an invalid stored value is found. */
internal class UpdateChannelSettings(private val preferences: SharedPreferences) {
    private val store = UpdateChannelStore(read = {
        runCatching { UpdateChannel.valueOf(preferences.getString(KEY, UpdateChannel.STABLE.name)!!) }
            .getOrDefault(UpdateChannel.STABLE)
    }, write = { channel ->
        check(preferences.edit().putString(KEY, channel.name).commit()) { "无法保存更新通道" }
    })
    fun get(): UpdateChannel = store.get()
    fun set(channel: UpdateChannel) = store.set(channel)

    companion object {
        private const val KEY = "update_channel"
        @Volatile private var instance: UpdateChannelSettings? = null
        fun from(context: Context): UpdateChannelSettings = instance ?: synchronized(this) {
            instance ?: UpdateChannelSettings(context.applicationContext.getSharedPreferences(
                "update_settings", Context.MODE_PRIVATE)).also { instance = it }
        }
    }
}
