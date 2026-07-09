package com.deivid22srk.grokproxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Foreground service that keeps the app process (and therefore the Go runtime
 * powering the proxy) alive while the user has the screen off or the app in
 * the background.
 *
 * The Go HTTP server itself is started/stopped through [Bridge] — this service
 * is a keep-alive shell with a persistent notification. It also ensures the
 * server is running when it enters the foreground (covering the
 * restart-after-process-kill case where Android resurrects the service with a
 * null intent).
 *
 * Lifecycle:
 *  - [ACTION_START] (or a null intent from a sticky restart): enter foreground,
 *    then make sure the Go server is up.
 *  - [ACTION_STOP]: stop the Go server, leave foreground, stopSelf.
 *
 * The foreground service type is `specialUse` (Android 14+) because a local
 * HTTP proxy doesn't fit any of the typed categories; the subtype is declared
 * in the manifest as required by the platform.
 */
class ProxyService : Service() {

    companion object {
        private const val CHANNEL_ID = "proxy_service"
        private const val NOTIF_ID = 1001

        const val ACTION_START = "com.deivid22srk.grokproxy.START"
        const val ACTION_STOP = "com.deivid22srk.grokproxy.STOP"

        /** Starts the foreground service (i.e. the keep-alive shell). */
        fun start(ctx: Context) {
            val i = Intent(ctx, ProxyService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        /** Stops the foreground service AND the Go server underneath it. */
        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, ProxyService::class.java).setAction(ACTION_STOP))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The Go runtime must be loaded before we touch any native function.
        if (!Bridge.ensureLoaded()) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Idempotent: safe to call on every start, including sticky restarts.
        Bridge.errorMessage(Bridge.nativeInit(filesDir.absolutePath))

        when (intent?.action) {
            ACTION_STOP -> {
                // Stop the Go server on a worker thread — Shutdown() blocks
                // until active requests drain and could take a while on a
                // streaming connection.
                Thread { try { Bridge.nativeStopServer() } catch (_: Throwable) {} }.start()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // ACTION_START or null (sticky restart): enter foreground first
                // to satisfy the 5-second startForeground deadline, then make
                // sure the Go server is up.
                startForegroundCompat()
                Thread {
                    try {
                        val s = Bridge.parseStatus(Bridge.nativeStatus())
                        if (!s.running) Bridge.errorMessage(Bridge.nativeStartServer("0.0.0.0:8787"))
                    } catch (_: Throwable) {}
                }.start()
            }
        }
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = getString(R.string.notif_channel_desc) }
            nm.createNotificationChannel(ch)
        }

        val openIntent = Intent(this, MainActivity::class.java)
        val openPI = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopPI = PendingIntent.getService(
            this, 1,
            Intent(this, ProxyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        // Read the current proxy address so the notification reflects reality.
        val addr = try {
            Bridge.parseStatus(Bridge.nativeStatus()).baseUrl
        } catch (_: Throwable) { "" }

        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(if (addr.isNotBlank()) addr else getString(R.string.notif_text))
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openPI)
            .addAction(0, getString(R.string.notif_action_stop), stopPI)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }
}
