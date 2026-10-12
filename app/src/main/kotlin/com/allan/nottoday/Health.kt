package com.allan.nottoday

import android.content.Context
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.provider.Settings
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Teste de saúde do filtro: resolve o domínio-canário pela rede da VPN (a mesma que os apps marcados usam). Se o
 * Pi-hole bloqueia o canário, o DNS da VPN é o Pi-hole. "Ok" vale por [OK_FOR_MS] depois do último sucesso e
 * cai após [FAILS_TO_DROP] falhas seguidas; sem novas verificações ele expira sozinho (falha fechada).
 */
object Health {
    private const val OK_FOR_MS = 150_000L
    private const val FAILS_TO_DROP = 2
    private const val TIMEOUT_S = 4L
    private val lock = Any()

    @Volatile var lastDetail: String = "sem verificação ainda"

    private fun prefs(context: Context) =
        context.createDeviceProtectedStorageContext().getSharedPreferences("health", Context.MODE_PRIVATE)

    private fun bootCount(context: Context) = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0)

    fun isOk(context: Context): Boolean {
        val p = prefs(context)
        return p.getInt("boot", -1) == bootCount(context) && SystemClock.elapsedRealtime() < p.getLong("ok_until", 0)
    }

    /** VPN caiu (ou filtro desligado): o "ok" anterior não vale mais. */
    fun invalidate(context: Context) = synchronized(lock) {
        prefs(context).edit().putLong("ok_until", 0).putInt("fails", 0).apply()
    }

    /** Roda o teste (bloqueia até ~4 s; chamar fora da thread principal) e registra o resultado. */
    fun check(context: Context): Boolean {
        val canary = Config(context).canary
        val (ok, detail) = if (canary.isEmpty()) false to "sem domínio-canário configurado" else probe(context, canary)
        lastDetail = detail
        synchronized(lock) {
            val p = prefs(context)
            val e = p.edit()
            if (ok) {
                e.putInt("fails", 0).putLong("ok_until", SystemClock.elapsedRealtime() + OK_FOR_MS).putInt("boot", bootCount(context))
            } else {
                val fails = p.getInt("fails", 0) + 1
                e.putInt("fails", fails)
                if (fails >= FAILS_TO_DROP) e.putLong("ok_until", 0)
            }
            e.apply()
        }
        return ok
    }

    @Suppress("DEPRECATION")
    private fun vpnNetwork(context: Context): Network? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        return cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    }

    private fun probe(context: Context, canary: String): Pair<Boolean, String> {
        val network = vpnNetwork(context) ?: return false to "sem VPN de pé"
        val latch = CountDownLatch(1)
        val answer = AtomicReference<ByteArray?>()
        val error = AtomicReference<String?>()
        try {
            DnsResolver.getInstance().rawQuery(
                network, canary, DnsResolver.CLASS_IN, DnsResolver.TYPE_A, DnsResolver.FLAG_NO_CACHE_LOOKUP,
                { it.run() }, null,
                object : DnsResolver.Callback<ByteArray> {
                    override fun onAnswer(a: ByteArray, rcode: Int) {
                        answer.set(a)
                        latch.countDown()
                    }

                    override fun onError(e: DnsResolver.DnsException) {
                        error.set("${e.javaClass.simpleName}: ${e.message}")
                        latch.countDown()
                    }
                },
            )
        } catch (e: Exception) {
            return false to "consulta falhou: ${e.javaClass.simpleName}: ${e.message}"
        }
        if (!latch.await(TIMEOUT_S, TimeUnit.SECONDS)) return false to "tempo esgotado ao resolver $canary pela VPN"
        val a = answer.get() ?: return false to "erro de DNS: ${error.get()}"
        return when (DnsProbe.verdict(a)) {
            DnsProbe.Verdict.BLOCKED -> true to "$canary bloqueado: o DNS da VPN é o servidor filtrante"
            DnsProbe.Verdict.ANSWERED -> false to "$canary RESOLVEU: o DNS da VPN não está bloqueando"
            DnsProbe.Verdict.FAILED -> false to "resposta DNS inválida para $canary"
        }
    }
}
