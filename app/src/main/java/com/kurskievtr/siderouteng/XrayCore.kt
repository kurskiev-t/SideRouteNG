package com.kurskievtr.siderouteng

import android.content.Context
import go.Seq
import libv2ray.Libv2ray

/** Native core setup, shared by the tunnel service and the upstream test. */
object XrayCore {
    private var initialized = false

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        val app = context.applicationContext
        Seq.setContext(app)
        Libv2ray.initCoreEnv(app.filesDir.absolutePath, "")
        initialized = true
    }

    /** Latency of the configured upstream in milliseconds, or null when it cannot be reached. */
    fun measure(context: Context, prefs: TunnelPrefs): Long? {
        val config = XrayConfig.buildProbe(prefs) ?: return null
        init(context)
        return runCatching { Libv2ray.measureOutboundDelay(config, XrayConfig.PROBE_URL) }
            .onFailure { AppLog.w("upstream test failed: ${it.message}") }
            .getOrNull()
            ?.takeIf { it > 0 }
    }
}
