package com.allan.nottoday

import kotlin.concurrent.thread

/** Roda trabalho de DPM (pode tocar a rede) fora da thread principal. */
fun runAsync(onDone: () -> Unit = {}, block: () -> Unit) {
    thread(name = "policy") {
        try {
            block()
        } catch (e: Throwable) {
            // Uma falha de política nunca pode derrubar o processo (o app precisa sempre abrir a tela do PIN).
            Diag.lastError = "${e.javaClass.simpleName}: ${e.message}"
        } finally {
            onDone()
        }
    }
}
