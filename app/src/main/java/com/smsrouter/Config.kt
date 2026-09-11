package com.smsrouter

import android.content.Context

const val SEC_SSL = 0
const val SEC_STARTTLS = 1
const val SEC_NONE = 2

fun defaultPort(security: Int): Int = when (security) {
    SEC_SSL -> 465
    SEC_STARTTLS -> 587
    else -> 25
}

data class SmtpConfig(
    val enabled: Boolean = false,
    val keepAlive: Boolean = true,
    val markRead: Boolean = true,
    val host: String = "",
    val port: Int = defaultPort(SEC_SSL),
    val security: Int = SEC_SSL,
    val user: String = "",
    val pass: String = "",
    val from: String = "",
    val to: String = ""
) {
    val isComplete: Boolean
        get() = host.isNotBlank() && user.isNotBlank() && pass.isNotBlank()
                && from.isNotBlank() && to.isNotBlank() && port in 1..65535
}

object Prefs {
    private const val FILE = "sms_router"
    private const val K_ENABLED = "enabled"
    private const val K_KEEPALIVE = "keep_alive"
    private const val K_MARK_READ = "mark_read"
    private const val K_HOST = "host"
    private const val K_PORT = "port"
    private const val K_SECURITY = "security"
    private const val K_USER = "user"
    private const val K_PASS = "pass"
    private const val K_FROM = "from"
    private const val K_TO = "to"

    fun load(context: Context): SmtpConfig {
        val sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val security = sp.getInt(K_SECURITY, SEC_SSL)
        return SmtpConfig(
            enabled = sp.getBoolean(K_ENABLED, false),
            keepAlive = sp.getBoolean(K_KEEPALIVE, true),
            markRead = sp.getBoolean(K_MARK_READ, true),
            host = sp.getString(K_HOST, "") ?: "",
            port = sp.getInt(K_PORT, defaultPort(security)),
            security = security,
            user = sp.getString(K_USER, "") ?: "",
            pass = sp.getString(K_PASS, "") ?: "",
            from = sp.getString(K_FROM, "") ?: "",
            to = sp.getString(K_TO, "") ?: ""
        )
    }

    fun save(context: Context, config: SmtpConfig) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(K_ENABLED, config.enabled)
            .putBoolean(K_KEEPALIVE, config.keepAlive)
            .putBoolean(K_MARK_READ, config.markRead)
            .putString(K_HOST, config.host.trim())
            .putInt(K_PORT, config.port)
            .putInt(K_SECURITY, config.security)
            .putString(K_USER, config.user.trim())
            .putString(K_PASS, config.pass)
            .putString(K_FROM, config.from.trim())
            .putString(K_TO, config.to.trim())
            .apply()
    }
}
