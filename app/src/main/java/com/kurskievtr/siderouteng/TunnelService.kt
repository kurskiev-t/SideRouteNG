package com.kurskievtr.siderouteng

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray

/**
 * Runs Xray-core on top of an Android TUN interface.
 *
 * The tunnel descriptor is handed to the core, so the core itself reads and writes IP packets and
 * relays both TCP and UDP. Nothing loops back into the tunnel because this package is excluded
 * from it, which is also what lets the core's own sockets reach the upstream server.
 */
class TunnelService : VpnService() {
    private lateinit var prefs: TunnelPrefs
    private var tun: ParcelFileDescriptor? = null
    private var controller: CoreController? = null
    private var stopped = false

    override fun onCreate() {
        super.onCreate()
        prefs = TunnelPrefs(this)
        XrayCore.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification())
        if (intent?.action == ACTION_DISCONNECT) {
            stop("requested from the UI")
            return START_NOT_STICKY
        }
        if (controller?.isRunning == true) return START_STICKY
        return if (start()) START_STICKY else START_NOT_STICKY
    }

    override fun onRevoke() {
        stop("VPN permission revoked by the system")
    }

    override fun onDestroy() {
        stop("service destroyed")
        super.onDestroy()
    }

    private fun start(): Boolean {
        val config = XrayConfig.build(prefs)
        if (config == null) {
            stop("no upstream configured")
            return false
        }
        val descriptor = establish()
        if (descriptor == null) {
            stop("cannot establish the tunnel")
            return false
        }
        tun = descriptor
        return try {
            val core = Libv2ray.newCoreController(Callback())
            controller = core
            core.startLoop(config.json, descriptor.fd)
            if (!core.isRunning) throw IllegalStateException("core did not start")
            prefs.enabled = true
            AppLog.i("xray started with ${config.outbounds} outbound(s)")
            true
        } catch (e: Exception) {
            stop("xray failed to start: ${e.message}")
            false
        }
    }

    private fun stop(reason: String) {
        if (stopped) return
        stopped = true
        AppLog.i("tunnel stopped: $reason")
        prefs.enabled = false
        try {
            controller?.takeIf { it.isRunning }?.stopLoop()
        } catch (e: Exception) {
            AppLog.w("xray stop failed: ${e.message}")
        }
        controller = null
        try {
            tun?.close()
        } catch (e: Exception) {
            AppLog.w("tunnel close failed: ${e.message}")
        }
        tun = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun establish(): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(prefs.mtu)
            .addAddress(prefs.tunnelIpv4Address, prefs.tunnelIpv4Prefix)
            .addRoute("0.0.0.0", 0)
        prefs.dnsServers.split(',', '\n').map(String::trim).filter { it.isNotEmpty() }
            .forEach { builder.addDnsServer(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)
        applyPerApp(builder)
        return try {
            builder.establish()
        } catch (e: IllegalStateException) {
            AppLog.e("establish failed: ${e.message}")
            null
        }
    }

    private fun applyPerApp(builder: Builder) {
        val selected = prefs.apps - packageName
        if (prefs.global || selected.isEmpty()) {
            builder.addDisallowedApplication(packageName)
            return
        }
        selected.forEach { app ->
            try {
                builder.addAllowedApplication(app)
            } catch (_: PackageManager.NameNotFoundException) {
                AppLog.w("app not installed: $app")
            }
        }
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.app_name),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_running))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private inner class Callback : CoreCallbackHandler {
        override fun startup(): Long = 0
        override fun shutdown(): Long {
            stop("core requested a shutdown")
            return 0
        }

        override fun onEmitStatus(code: Long, message: String?): Long {
            message?.takeIf { it.isNotBlank() }?.let { AppLog.i("xray: $it") }
            return 0
        }
    }

    companion object {
        const val ACTION_CONNECT = "com.kurskievtr.siderouteng.CONNECT"
        const val ACTION_DISCONNECT = "com.kurskievtr.siderouteng.DISCONNECT"
        private const val CHANNEL_ID = "tunnel"
        private const val NOTIFICATION_ID = 1

        fun enqueue(context: Context, action: String) {
            val intent = Intent(context, TunnelService::class.java).setAction(action)
            context.startForegroundService(intent)
        }
    }
}
