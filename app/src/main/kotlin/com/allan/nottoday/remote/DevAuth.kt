package com.allan.nottoday.remote

/** Identificador do aparelho e requisições assinadas ao gateway (veja protocol/SPEC.md no repositório guardian). */
object DevAuth {
    private const val B32 = "abcdefghijklmnopqrstuvwxyz234567"

    /** base32(SHA-256(SPKI)) minúsculo, sem preenchimento, primeiros 20 caracteres. */
    fun deviceId(spki: ByteArray): String {
        val d = WebAuthn.sha256(spki)
        val sb = StringBuilder()
        var buf = 0
        var bits = 0
        for (b in d) {
            buf = (buf shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                sb.append(B32[(buf shr (bits - 5)) and 31])
                bits -= 5
            }
            buf = buf and ((1 shl bits) - 1)
        }
        if (bits > 0) sb.append(B32[(buf shl (5 - bits)) and 31])
        return sb.substring(0, 20)
    }

    fun fingerprint(spki: ByteArray): String = deviceId(spki).chunked(4).joinToString("-")

    fun signingString(method: String, path: String, ts: String, nonce: String, body: ByteArray): ByteArray {
        val hex = WebAuthn.sha256(body).joinToString("") { "%02x".format(it) }
        return "$method\n$path\n$ts\n$nonce\n$hex".toByteArray(Charsets.UTF_8)
    }

    /** Cabeçalhos X-Dev/X-Ts/X-Nonce/X-Sig. `sign` devolve ECDSA-SHA256 em DER. `path` inclui a query. */
    fun headers(
        sign: (ByteArray) -> ByteArray, spki: ByteArray, method: String, path: String, body: ByteArray, ts: Long, nonce: String,
    ): Map<String, String> = linkedMapOf(
        "X-Dev" to deviceId(spki), "X-Ts" to ts.toString(), "X-Nonce" to nonce,
        "X-Sig" to B64.enc(sign(signingString(method, path, ts.toString(), nonce, body))),
    )
}
