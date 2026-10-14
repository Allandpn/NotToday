package com.allan.nottoday.remote

import android.content.Context
import com.allan.nottoday.Diag
import java.io.IOException

/**
 * Canal com o gateway: relata o estado, faz long-poll da fila de comandos assinados e os executa.
 * Roda no serviço de guarda (Device Owner) e, antes do Device Owner, enquanto a tela está aberta (pareamento).
 * Sai sozinho se o pareamento for desfeito.
 */
object RemoteChannel {
    private const val POLL_S = 25

    /** Resumo para a tela. */
    @Volatile var status: String = "parado"

    /** Cada start/stop troca a geração; uma thread antiga percebe e sai, então nunca há duas rodando. */
    @Volatile private var generation = 0
    private var thread: Thread? = null

    @Synchronized
    fun start(context: Context) {
        if (thread?.isAlive == true) return
        val app = context.applicationContext
        if (!RemoteStore(app).isPaired) return
        val gen = ++generation
        thread = Thread({ loop(app, gen) }, "remote").also {
            it.isDaemon = true
            it.start()
        }
    }

    @Synchronized
    fun stop() {
        generation++
        thread?.interrupt()
        thread = null
        status = "parado"
    }

    private fun loop(app: Context, gen: Int) {
        val store = RemoteStore(app)
        var session: RemoteSession? = null
        var backoff = 2_000L
        while (gen == generation) {
            try {
                if (!store.isPaired) break
                val s = session ?: RemoteSession(app, store, GatewayApi(store.gateway ?: break)).also { session = it }
                s.cycle(POLL_S)
                status = "conectado"
                backoff = 2_000L
            } catch (e: InterruptedException) {
                break
            } catch (e: GatewayException) {
                status = "gateway recusou: ${e.message}"
                sleepBackoff(backoff)
                backoff = (backoff * 2).coerceAtMost(60_000L)
            } catch (e: IOException) {
                status = "sem contato: ${e.message}"
                sleepBackoff(backoff)
                backoff = (backoff * 2).coerceAtMost(60_000L)
            } catch (e: Throwable) {
                Diag.lastError = "RemoteChannel: ${e.javaClass.simpleName}: ${e.message}"
                status = "erro: ${e.javaClass.simpleName}"
                sleepBackoff(backoff)
                backoff = (backoff * 2).coerceAtMost(60_000L)
            }
        }
    }

    private fun sleepBackoff(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            // stop() interrompe: o laço confere a geração e sai
        }
    }
}
