package me.bechberger.phoneserver.manager

import android.content.Context

object ServerSettings {
    private const val PREFS_NAME = "server_settings"
    private const val KEY_PORT = "server_port"
    const val DEFAULT_PORT = 8005

    fun getPort(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_PORT, DEFAULT_PORT)

    fun setPort(context: Context, port: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_PORT, port).apply()
    }

    fun isPortAvailable(port: Int): Boolean = try {
        java.net.ServerSocket(port).use { true }
    } catch (e: Exception) {
        false
    }
}
