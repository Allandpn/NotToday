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

    private fun report(consumer: Consumer) {
        api.call("POST", "/v1/devices/${consumer.dev}/report", RemoteReport.build(app, consumer, executed))
        executed.clear()
        lastReport = System.currentTimeMillis()
    }

    private companion object {
        const val REPORT_EVERY_MS = 60_000L
    }
}
