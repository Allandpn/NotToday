package com.allan.nottoday

/** Último erro inesperado em segundo plano; aparece na tela para facilitar o diagnóstico. */
object Diag {
    @Volatile var lastError: String? = null
}
