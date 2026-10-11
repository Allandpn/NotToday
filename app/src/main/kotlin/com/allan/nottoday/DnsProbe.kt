package com.allan.nottoday

/**
 * Interpreta a resposta DNS do domínio-canário (um domínio REAL que o Pi-hole mantém bloqueado).
 * BLOCKED: NXDOMAIN, resposta vazia ou só endereços nulos (0.0.0.0 / ::), os modos de bloqueio NULL/NXDOMAIN/NODATA do Pi-hole.
 * ANSWERED: algum endereço de verdade, ou seja, o DNS usado NÃO está bloqueando o canário.
 * FAILED: resposta inválida ou erro de servidor (SERVFAIL, REFUSED…).
 */
object DnsProbe {
    enum class Verdict { BLOCKED, ANSWERED, FAILED }

    fun verdict(resp: ByteArray): Verdict {
        if (resp.size < 12) return Verdict.FAILED
        val rcode = resp[3].toInt() and 0x0F
        if (rcode == 3) return Verdict.BLOCKED
        if (rcode != 0) return Verdict.FAILED
        val questions = u16(resp, 4)
        val answers = u16(resp, 6)
        var off = 12
        repeat(questions) {
            off = skipName(resp, off) ?: return Verdict.FAILED
            off += 4
        }
        if (answers == 0) return Verdict.BLOCKED
        var real = false
        repeat(answers) {
            off = skipName(resp, off) ?: return Verdict.FAILED
            if (off + 10 > resp.size) return Verdict.FAILED
            val type = u16(resp, off)
            val len = u16(resp, off + 8)
            off += 10
            if (off + len > resp.size) return Verdict.FAILED
            val isAddress = (type == 1 && len == 4) || (type == 28 && len == 16)
            if (isAddress && (off until off + len).any { resp[it].toInt() != 0 }) real = true
            off += len
        }
        return if (real) Verdict.ANSWERED else Verdict.BLOCKED
    }

    private fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    private fun skipName(b: ByteArray, start: Int): Int? {
        var o = start
        while (true) {
            if (o >= b.size) return null
            val len = b[o].toInt() and 0xFF
            when {
                len == 0 -> return o + 1
                (len and 0xC0) == 0xC0 -> return if (o + 2 <= b.size) o + 2 else null
                else -> o += len + 1
            }
        }
    }
}
