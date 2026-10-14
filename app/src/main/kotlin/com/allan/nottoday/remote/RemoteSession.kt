package com.allan.nottoday.remote

import android.content.Context
import java.security.PrivateKey

/**
 * Um ciclo do canal: relata o estado quando é hora, consulta a fila (long-poll), verifica e executa os comandos.
 * Separado do laço de threads ([RemoteChannel]) para ser testável contra um gateway de verdade.
 */
class RemoteSession(
    private val app: Context,
    private val store: RemoteStore,
    private val api: GatewayApi,
    private val notify: (String) -> Unit = { UnblockNotifier.show(app, it) },
    private val privateKey: () -> PrivateKey = { DeviceKey.privateKey() },
) {
    private val executed = ArrayList<Map<String, Any?>>()
    private var lastReport = 0L

    /** Faz o ciclo e devolve quantos comandos foram tratados. */
    fun cycle(waitS: Int): Int {
        val consumer = store.consumer() ?: throw GatewayException(0, "sem pareamento")
        val dev = consumer.dev
        if (System.currentTimeMillis() - lastReport >= REPORT_EVERY_MS) report(consumer)

        val got = api.call("GET", "/v1/devices/$dev/commands?after=${store.after}&wait=$waitS", waitS = waitS)
        store.lastContact = System.currentTimeMillis()
        checkAnswers(dev)
        val commands = got["commands"] as? List<*> ?: return 0
        if (commands.isEmpty()) return 0
        val runner = RemoteCommands(app, store, consumer, privateKey)
        var n = 0
        for (c in commands) {
            @Suppress("UNCHECKED_CAST")
            val m = c as? Map<String, Any?> ?: continue
            @Suppress("UNCHECKED_CAST")
            val env = m["envelope"] as? Map<String, Any?> ?: continue
            executed.add(runner.handle(env).toMap())
            (m["seq"] as? Long)?.let { if (it > store.after) store.after = it }
            n++
            if (!store.isPaired) break // um comando desfez o pareamento (ex.: remoção do Device Owner)
        }
        lastReport = 0L // relata já o resultado dos comandos
        return n
    }

    /** Pedidos de liberação em aberto: quando o guardião responde, avisa e para de acompanhar. Falha aqui não derruba o ciclo. */
    private fun checkAnswers(dev: String) {
        val watched = store.watched
        if (watched.isEmpty()) return
        try {
            val now = UnblockApi(api, dev).requests().associateBy { it.id }
            val still = HashSet<Long>()
            for (id in watched) {
                val r = now[id]
                if (r == null || r.status != "open") r?.let { UnblockText.answer(it)?.let(notify) } else still.add(id)
            }
            store.watched = still
        } catch (_: GatewayException) {
            // tenta de novo no próximo ciclo
        } catch (_: java.io.IOException) {
        }
    }

    private fun report(consumer: Consumer) {
        api.call("POST", "/v1/devices/${consumer.dev}/report", RemoteReport.build(app, consumer, executed))
        executed.clear()
        lastReport = System.currentTimeMillis()
    }

    private companion object {
        const val REPORT_EVERY_MS = 60_000L
    }
}
