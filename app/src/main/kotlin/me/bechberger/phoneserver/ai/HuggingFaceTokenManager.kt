package me.bechberger.phoneserver.ai

import android.content.Context
import android.content.SharedPreferences
import timber.log.Timber

/**
 * Manages the HuggingFace API token for authenticated model downloads.
 * Token is persisted in SharedPreferences and used automatically when
 * downloading models that require authentication (needsAuth = true).
 */
class HuggingFaceTokenManager private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "hf_token_prefs"
        private const val KEY_TOKEN = "hf_access_token"

        @Volatile
        private var instance: HuggingFaceTokenManager? = null

        fun getInstance(context: Context): HuggingFaceTokenManager {
            return instance ?: synchronized(this) {
                instance ?: HuggingFaceTokenManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    /** Returns the stored HF token, or null if not set. */
    fun getToken(): String? = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }

    /** Returns true if a token is stored. */
    fun hasToken(): Boolean = getToken() != null

    /**
     * Saves the HF token.
     * @param token A HuggingFace access token (starts with "hf_…").
     */
    fun setToken(token: String) {
        prefs.edit().putString(KEY_TOKEN, token.trim()).apply()
        Timber.i("HuggingFace token saved (length=${token.trim().length})")
    }

    /** Removes the stored token. */
    fun clearToken() {
        prefs.edit().remove(KEY_TOKEN).apply()
        Timber.i("HuggingFace token cleared")
    }

    /**
     * Returns the token masked for safe logging/display, e.g. "hf_abc…xyz".
     */
    fun getMaskedToken(): String? {
        val token = getToken() ?: return null
        return if (token.length > 8) "${token.take(6)}…${token.takeLast(4)}" else "****"
    }
}
