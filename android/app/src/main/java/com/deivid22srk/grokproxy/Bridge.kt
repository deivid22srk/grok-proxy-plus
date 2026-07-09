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
    @JvmStatic external fun nativeGetUsage(): String

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

    // ---- Usage models --------------------------------------------------

    data class RateLimits(
        val limitRequests: Long,
        val remainingRequests: Long,
        val limitTokens: Long,
        val remainingTokens: Long,
        val lastModel: String,
        val lastUpdated: String,
        val hasData: Boolean,
    )

    data class Billing(
        val used: Long,
        val monthlyLimit: Long,
        val onDemandCap: Long,
        val periodStart: String,
        val periodEnd: String,
        val history: List<BillingHistory>,
    )

    data class BillingHistory(
        val year: Int,
        val month: Int,
        val includedUsed: Long,
        val onDemandUsed: Long,
        val totalUsed: Long,
    )

    data class UserInfo(
        val email: String,
        val firstName: String,
        val hasGrokCodeAccess: Boolean,
        val userId: String,
    )

    data class Usage(
        val rateLimits: RateLimits?,
        val billing: Billing?,
        val user: UserInfo?,
        val accountBlocked: Boolean,
        val email: String,
        val error: String?,
    )

    /** Parses the JSON envelope returned by nativeGetUsage(). */
    fun parseUsage(raw: String): Usage {
        val o = JSONObject(raw)
        val rlObj = o.optJSONObject("rate_limits")
        val rateLimits = if (rlObj != null) RateLimits(
            limitRequests = rlObj.optLong("limit_requests"),
            remainingRequests = rlObj.optLong("remaining_requests"),
            limitTokens = rlObj.optLong("limit_tokens"),
            remainingTokens = rlObj.optLong("remaining_tokens"),
            lastModel = rlObj.optString("last_model"),
            lastUpdated = rlObj.optString("last_updated"),
            hasData = rlObj.optBoolean("has_data"),
        ) else null

        val billingObj = o.optJSONObject("billing")
        val billing = if (billingObj != null) {
            val histArr = billingObj.optJSONArray("history")
            val history = mutableListOf<BillingHistory>()
            if (histArr != null) {
                for (i in 0 until histArr.length()) {
                    val h = histArr.optJSONObject(i) ?: continue
                    history.add(BillingHistory(
                        year = h.optInt("year"),
                        month = h.optInt("month"),
                        includedUsed = h.optLong("included_used"),
                        onDemandUsed = h.optLong("on_demand_used"),
                        totalUsed = h.optLong("total_used"),
                    ))
                }
            }
            Billing(
                used = billingObj.optLong("used"),
                monthlyLimit = billingObj.optLong("monthly_limit"),
                onDemandCap = billingObj.optLong("on_demand_cap"),
                periodStart = billingObj.optString("billing_period_start"),
                periodEnd = billingObj.optString("billing_period_end"),
                history = history,
            )
        } else null

        val userObj = o.optJSONObject("user")
        val user = if (userObj != null) UserInfo(
            email = userObj.optString("email"),
            firstName = userObj.optString("first_name"),
            hasGrokCodeAccess = userObj.optBoolean("has_grok_code_access"),
            userId = userObj.optString("user_id"),
        ) else null

        return Usage(
            rateLimits = rateLimits,
            billing = billing,
            user = user,
            accountBlocked = o.optBoolean("account_blocked"),
            email = o.optString("email"),
            error = if (o.has("error")) o.optString("error") else null,
        )
    }
}
