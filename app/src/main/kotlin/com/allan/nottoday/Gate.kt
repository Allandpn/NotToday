package com.allan.nottoday

enum class Change { LOOSEN, TIGHTEN, NEUTRAL }

/**
 * Anti-impulso: alterações que AFROUXAM a proteção não valem na hora; viram um pedido que só pode ser confirmado
 * depois de [Config.unlockDelayMin]. Alterações que endurecem valem imediatamente.
 * Trabalha sobre o "snapshot" da configuração (mapa chave → texto) e não depende do Android.
 */
object Gate {
    const val KEY_DELAY = "unlock_delay_min"
    private val NEUTRAL_KEYS = setOf("postpone_updates")

    /** Números em que "maior" dá mais acesso ao desenvolvedor. */
    private val HIGHER_LOOSENS = setOf("dev_windows_per_day", "dev_window_seconds")

    /** Numeros em que "menor" da mais acesso (ex.: nivel do YouTube restrito: 2 estrito, 0 desligado). */
    private val LOWER_LOOSENS = setOf("chrome_youtube")

    private fun csv(s: String?) = s.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    fun classify(key: String, old: String?, new: String): Change = when {
        old == new -> Change.NEUTRAL
        key in NEUTRAL_KEYS -> Change.NEUTRAL
        // O 1º canário é a configuração inicial (endurece); trocar um canário existente afrouxa.
        key == "canary" -> if (old.isNullOrBlank()) Change.TIGHTEN else Change.LOOSEN
        key == KEY_DELAY ->
            if ((new.toLongOrNull() ?: 0) < (old?.toLongOrNull() ?: 0)) Change.LOOSEN else Change.TIGHTEN
        key in HIGHER_LOOSENS ->
            if ((new.toLongOrNull() ?: 0) > (old?.toLongOrNull() ?: 0)) Change.LOOSEN else Change.TIGHTEN
        key in LOWER_LOOSENS ->
            if ((new.toLongOrNull() ?: 0) < (old?.toLongOrNull() ?: 0)) Change.LOOSEN else Change.TIGHTEN
        // Tirar itens destas listas reduz a proteção.
        key == "protected_pkgs" || key == "routed_extra" || key == "store_extra" ->
            if ((csv(old) - csv(new)).isNotEmpty()) Change.LOOSEN else Change.TIGHTEN
        // Navegador isento = navegador fora do filtro: acrescentar afrouxa, tirar da lista endurece.
        key == "browsers_exempt" -> if ((csv(new) - csv(old)).isNotEmpty()) Change.LOOSEN else Change.TIGHTEN
        // Demais chaves são booleanas e "ligado" é o lado mais protegido.
        old == "true" && new == "false" -> Change.LOOSEN
        else -> Change.TIGHTEN
    }

    class Split(val immediate: Map<String, String>, val delayed: Map<String, String>)

    fun split(old: Map<String, String>, new: Map<String, String>): Split {
        val delayed = LinkedHashMap<String, String>()
        val immediate = LinkedHashMap<String, String>()
        for ((k, v) in new) {
            if (old[k] == v) continue
            if (classify(k, old[k], v) == Change.LOOSEN) delayed[k] = v else immediate[k] = v
        }
        return Split(immediate, delayed)
    }
}

/** Relógios no instante da consulta. `wallTrusted`: hora automática ligada e `no_config_date_time` ativo. */
class Clock(val elapsed: Long, val wall: Long, val boot: Int, val wallTrusted: Boolean)

/** Um desbloqueio pedido, à espera do atraso. */
class Pending(
    val kind: Kind,
    val payload: Map<String, String>,
    val requestedElapsed: Long,
    val requestedWall: Long,
    val boot: Int,
    val delayMs: Long,
) {
    enum class Kind { SETTINGS, RELEASE, MAINTENANCE, GUARDIAN }

    sealed class Status {
        data class Waiting(val remainingMs: Long) : Status()
        data class Ready(val expiresInMs: Long) : Status()
        data object Expired : Status()
    }

    /**
     * Tempo esperado = o maior entre o relógio monotônico (vale só no mesmo boot) e o relógio de parede (vale só
     * se a hora estiver travada/automática). Depois de reiniciar sem hora confiável, a contagem recomeça.
     */
    fun status(c: Clock): Status {
        val byElapsed = if (c.boot == boot) c.elapsed - requestedElapsed else null
        val byWall = if (c.wallTrusted) c.wall - requestedWall else null
        val waited = listOfNotNull(byElapsed, byWall).maxOrNull()?.coerceAtLeast(0) ?: 0
        return when {
            waited < delayMs -> Status.Waiting(delayMs - waited)
            waited < delayMs + READY_VALID_MS -> Status.Ready(delayMs + READY_VALID_MS - waited)
            else -> Status.Expired
        }
    }

    fun serialize(): String = buildString {
        append("$kind\t$delayMs\t$requestedElapsed\t$requestedWall\t$boot\n")
        payload.forEach { (k, v) -> append(clean(k)).append('\t').append(clean(v)).append('\n') }
    }

    companion object {
        /** Depois de liberado, o pedido vale por este tempo (não dá para "estocar" desbloqueios). */
        const val READY_VALID_MS = 24L * 60 * 60 * 1000

        private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ')

        fun parse(text: String?): Pending? {
            val lines = text?.lines()?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() } ?: return null
            val h = lines[0].split('\t')
            if (h.size != 5) return null
            val kind = Kind.entries.firstOrNull { it.name == h[0] } ?: return null
            val payload = LinkedHashMap<String, String>()
            lines.drop(1).forEach {
                val p = it.split('\t', limit = 2)
                if (p.size == 2) payload[p[0]] = p[1]
            }
            return Pending(
                kind, payload,
                h[2].toLongOrNull() ?: return null, h[3].toLongOrNull() ?: return null,
                h[4].toIntOrNull() ?: return null, h[1].toLongOrNull() ?: return null,
            )
        }
    }
}
