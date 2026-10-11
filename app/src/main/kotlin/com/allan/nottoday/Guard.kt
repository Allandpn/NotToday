package com.allan.nottoday

import android.content.Context
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings

object Clocks {
    fun now(context: Context): Clock {
        val cr = context.contentResolver
        val autoTime = Settings.Global.getInt(cr, Settings.Global.AUTO_TIME, 0) == 1
        val dateLocked = Config(context).let { c ->
            Catalog.restrictions.firstOrNull { it.key == UserManager.DISALLOW_CONFIG_DATE_TIME }?.let(c::isRestrictionOn) == true
        }
        return Clock(
            elapsed = SystemClock.elapsedRealtime(),
            wall = System.currentTimeMillis(),
            boot = Settings.Global.getInt(cr, Settings.Global.BOOT_COUNT, 0),
            wallTrusted = autoTime && dateLocked,
        )
    }
}

/** Pedidos de desbloqueio pendentes (um por tipo), persistidos. */
class PendingStore(private val context: Context) {
    private val prefs = context.createDeviceProtectedStorageContext()
        .getSharedPreferences("pending", Context.MODE_PRIVATE)

    fun get(kind: Pending.Kind): Pending? = Pending.parse(prefs.getString(kind.name, null))

    fun put(kind: Pending.Kind, payload: Map<String, String>, delayMs: Long) {
        val c = Clocks.now(context)
        val p = Pending(kind, payload, c.elapsed, c.wall, c.boot, delayMs)
        prefs.edit().putString(kind.name, p.serialize()).apply()
    }

    fun clear(kind: Pending.Kind) = prefs.edit().remove(kind.name).apply()

    fun clearAll() = prefs.edit().clear().apply()
}

/**
 * Janela de desenvolvimento: abre só o ADB por poucos segundos (PIN de desenvolvedor), no máximo N vezes por 24 h,
 * e fecha sozinha (opcionalmente reiniciando o aparelho). A contagem não zera com reinício.
 */
class DevWindow(private val context: Context) {
    private val prefs = context.createDeviceProtectedStorageContext()
        .getSharedPreferences("devwin", Context.MODE_PRIVATE)

    fun remainingMs(): Long {
        val c = Clocks.now(context)
        if (!prefs.getBoolean("open", false) || prefs.getInt("boot", -1) != c.boot) return 0
        return (prefs.getLong("end_elapsed", 0) - c.elapsed).coerceAtLeast(0)
    }

    fun isActive() = remainingMs() > 0

    private class Start(val boot: Int, val elapsed: Long, val wall: Long)

    private fun starts(): List<Start> = prefs.getString("starts", null).orEmpty().split(';').mapNotNull {
        val p = it.split(',')
        if (p.size != 3) null else Start(p[0].toIntOrNull() ?: return@mapNotNull null, p[1].toLongOrNull() ?: return@mapNotNull null,
            p[2].toLongOrNull() ?: return@mapNotNull null)
    }

    /** Janelas abertas nas últimas 24 h. Após reinício sem hora confiável, conta como recente (conservador). */
    fun usedInLast24h(): Int {
        val c = Clocks.now(context)
        return starts().count { s ->
            when {
                s.boot == c.boot -> c.elapsed - s.elapsed < DAY || (c.wallTrusted && c.wall - s.wall < DAY)
                c.wallTrusted -> c.wall - s.wall < DAY
                else -> true
            }
        }
    }

    fun canStart(config: Config) = !isActive() && usedInLast24h() < config.devWindowsPerDay

    fun start(seconds: Int) {
        val c = Clocks.now(context)
        val all = (starts().takeLast(19).map { "${it.boot},${it.elapsed},${it.wall}" } + "${c.boot},${c.elapsed},${c.wall}")
        prefs.edit()
            .putString("starts", all.joinToString(";"))
            .putLong("end_elapsed", c.elapsed + seconds * 1000L)
            .putInt("boot", c.boot)
            .putBoolean("open", true)
            .apply()
    }

    /** Há uma janela aberta que já terminou? `rebootNeeded` é falso se o aparelho já reiniciou desde que abriu. */
    fun closingState(): Closing? {
        if (!prefs.getBoolean("open", false) || isActive()) return null
        return Closing(rebootNeeded = prefs.getInt("boot", -1) == Clocks.now(context).boot)
    }

    class Closing(val rebootNeeded: Boolean)

    fun markClosed() = prefs.edit().putBoolean("open", false).remove("end_elapsed").apply()

    fun clear() = prefs.edit().clear().apply()

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000
    }
}

/**
 * Janela de manutenção: por alguns minutos libera o que é preciso para depurar e atualizar apps
 * (ADB, instalar apps, fontes desconhecidas, USB, lojas). Termina sozinha; reiniciar também a encerra.
 */
class Maintenance(private val context: Context) {
    private val prefs = context.createDeviceProtectedStorageContext()
        .getSharedPreferences("maintenance", Context.MODE_PRIVATE)

    fun start(minutes: Long) {
        val c = Clocks.now(context)
        prefs.edit().putLong("end_elapsed", c.elapsed + minutes * 60_000L).putInt("boot", c.boot).apply()
    }

    fun stop() = prefs.edit().clear().apply()

    fun remainingMs(): Long {
        val c = Clocks.now(context)
        if (!prefs.contains("end_elapsed") || prefs.getInt("boot", -1) != c.boot) return 0
        return (prefs.getLong("end_elapsed", 0) - c.elapsed).coerceAtLeast(0)
    }

    fun isActive() = remainingMs() > 0

    companion object {
        const val DEFAULT_MINUTES = 30L

        /** Restrições suspensas durante a janela. */
        val RESTRICTION_KEYS = setOf(
            UserManager.DISALLOW_DEBUGGING_FEATURES,
            UserManager.DISALLOW_INSTALL_APPS,
            UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
            UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY,
            UserManager.DISALLOW_USB_FILE_TRANSFER,
        )
    }
}
