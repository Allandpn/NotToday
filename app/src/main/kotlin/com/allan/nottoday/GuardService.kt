package com.allan.nottoday

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder

/**
 * Serviço em primeiro plano que mantém o app vivo: reage na hora à instalação de pacotes (app novo → suspenso),
 * repete o teste de saúde a cada ~20 s e fecha a janela de desenvolvimento no instante certo.
 * O alarme do [Watchdog] o reinicia se o sistema o matar.
 */
class GuardService : Service() {
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            handler?.post { runSafely { PolicyManager(this@GuardService).quickSync() } }
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            runSafely { PolicyManager(this@GuardService).heartbeat() }
            val left = DevWindow(this@GuardService).remainingMs()
            handler?.postDelayed(this, if (left > 0) left.coerceIn(500, 5_000) else TICK_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "NotToday", NotificationManager.IMPORTANCE_LOW))
        thread = HandlerThread("guard").also { it.start() }
        handler = Handler(thread!!.looper)
        val filter = IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply { addDataScheme("package") }
        registerReceiver(packageReceiver, filter, RECEIVER_NOT_EXPORTED)
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val notification = Notification.Builder(this, CHANNEL)
                .setContentTitle("NotToday ativo")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setOngoing(true)
                .build()
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } catch (e: Exception) {
            Diag.lastError = "GuardService: ${e.javaClass.simpleName}: ${e.message}"
        }
        if (!PolicyManager(this).isDeviceOwner()) {
            stopSelf()
            return START_NOT_STICKY
        }
        com.allan.nottoday.remote.RemoteChannel.start(this)
        // Cada (re)início roda um batimento imediato e recalcula o intervalo.
        handler?.removeCallbacks(ticker)
        handler?.post(ticker)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        com.allan.nottoday.remote.RemoteChannel.stop()
        try {
            unregisterReceiver(packageReceiver)
        } catch (_: Exception) {
        }
        thread?.quitSafely()
        super.onDestroy()
    }

    private inline fun runSafely(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            Diag.lastError = "${e.javaClass.simpleName}: ${e.message}"
        }
    }

    companion object {
        private const val CHANNEL = "guard"
        private const val NOTIFICATION_ID = 1
        private const val TICK_MS = 20_000L

        /** Verdadeiro enquanto o serviço está de pé neste processo. */
        @Volatile var running = false

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, GuardService::class.java))
            } catch (e: Exception) {
                Diag.lastError = "GuardService.start: ${e.javaClass.simpleName}: ${e.message}"
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, GuardService::class.java))
            } catch (_: Exception) {
            }
        }
    }
}
