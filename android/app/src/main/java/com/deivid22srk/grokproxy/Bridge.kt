package com.deivid22srk.grokproxy

import org.json.JSONObject

/**
 * JNI bridge to the native Go library (libgrokproxy.so).
 *
 * The library is built with `go build -buildmode=c-shared` from the `mobile/`
 * package in this repo — full Go, NOT gomobile. Every native function returns a
 * JSON string so the Kotlin side never has to touch raw pointers.
 *
 * The exported C symbols follow the JNI naming convention:
 *   Java_com_deivid22srk_grokproxy_Bridge_<method>
 * which Kotlin matches with `@JvmStatic external fun`.
 */
object Bridge {

    @Volatile
    private var loaded: Boolean = false

    /** Loads the native library. Safe to call multiple times. */
    fun ensureLoaded(): Boolean {
        if (loaded) return true
        return try {
            System.loadLibrary("grokproxy")
            loaded = true
            true
        } catch (t: Throwable) {
            // Missing ABI / corrupt .so — surface to the UI via lastError.
            lastError = t.message ?: "failed to load libgrokproxy"
            false
        }
    }

    @Volatile
    var lastError: String? = null

    @JvmStatic external fun nativeInit(dataDir: String): String
    @JvmStatic external fun nativeStartServer(listen: String): String
    @JvmStatic external fun nativeStopServer(): String
    @JvmStatic external fun nativeStatus(): String
    @JvmStatic external fun nativeStartLogin(): String
    @JvmStatic external fun nativeLoginStatus(): String
    @JvmStatic external fun nativeCancelLogin(): String
    @JvmStatic external fun nativeListAccounts(): String
    @JvmStatic external fun nativeLogout(id: String): String
    @JvmStatic external fun nativeSetActive(id: String): String

    // ---- Parsed models -------------------------------------------------

    data class Account(
        val id: String,
        val label: String,
        val email: String,
        val active: Boolean,
        val expired: Boolean,
    )

    data class Status(
        val running: Boolean,
        val addr: String,
        val baseUrl: String,
        val apiKey: String,
        val model: String,
        val activeEmail: String,
        val activeLabel: String,
        val accounts: List<Account>,
        val loginState: String,   // idle | pending | success | error
        val loginUrl: String,
        val loginCode: String,
        val loginEmail: String,
        val loginError: String,
        val dataDir: String,
        val error: String?,
    )

    /** Parses the JSON envelope returned by nativeStatus(). */
    fun parseStatus(raw: String): Status {
        val o = JSONObject(raw)
        val accounts = mutableListOf<Account>()
        val arr = o.optJSONArray("accounts")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val a = arr.optJSONObject(i) ?: continue
                accounts.add(
                    Account(
                        id = a.optString("id"),
                        label = a.optString("label").ifBlank { a.optString("email") },
                        email = a.optString("email"),
                        active = a.optBoolean("active"),
                        expired = a.optBoolean("expired"),
                    )
                )
            }
        }
        return Status(
            running = o.optBoolean("running"),
            addr = o.optString("addr"),
            baseUrl = o.optString("base_url"),
            apiKey = o.optString("api_key"),
            model = o.optString("model"),
            activeEmail = o.optString("active_email"),
            activeLabel = o.optString("active_label"),
            accounts = accounts,
            loginState = o.optString("login_state", "idle"),
            loginUrl = o.optString("login_url"),
            loginCode = o.optString("login_code"),
            loginEmail = o.optString("login_email"),
            loginError = o.optString("login_error"),
            dataDir = o.optString("data_dir"),
            error = if (o.has("error")) o.optString("error") else null,
        )
    }

    /** Returns the error message inside {"error": "..."}, or null on {"ok": true}. */
    fun errorMessage(raw: String): String? {
        val o = JSONObject(raw)
        return if (o.has("error")) o.optString("error") else null
    }
}
