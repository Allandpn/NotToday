package com.allan.nottoday

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * Cobre o caso em que o processo morre sem rodar nenhum código de limpeza: um alarme (que sobrevive à morte do
 * processo) acorda o app a cada ~1 min, reinicia o serviço de guarda e reaplica a política de tempos em tempos.
 */
object Watchdog {
    private const val INTERVAL_MS = 60_000L

    /** Último reaplicar completo neste processo (processo novo = 0, então reaplica no 1º tick). */
    @Volatile var lastFullApply = 0L

    fun schedule(context: Context) {
        context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + INTERVAL_MS,
            pending(context),
        )
    }

    fun cancel(context: Context) = context.getSystemService(AlarmManager::class.java).cancel(pending(context))

    private fun pending(context: Context) = PendingIntent.getBroadcast(
        context, 0, Intent(context, WatchdogReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

private const val FULL_APPLY_EVERY_MS = 5 * 60_000L

class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val policy = PolicyManager(context)
        if (!policy.isDeviceOwner()) return
        Watchdog.schedule(context)
        GuardService.start(context)
        val pending = goAsync()
        runAsync(onDone = pending::finish) {
            // Reaplica a política inteira de tempos em tempos (hora/estado burlados, janela de manutenção que
            // acabou, novos navegadores…); nos demais ticks só o batimento (saúde + suspensões) se o serviço
            // de guarda não está rodando neste processo.
            val now = SystemClock.elapsedRealtime()
            if (now - Watchdog.lastFullApply > FULL_APPLY_EVERY_MS) {
                Watchdog.lastFullApply = now
                policy.apply()
            } else if (!GuardService.running) {
                policy.heartbeat()
            }
        }
    }
}
