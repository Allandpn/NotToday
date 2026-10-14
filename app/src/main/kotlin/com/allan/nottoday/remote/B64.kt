package com.allan.nottoday.remote

import java.util.Base64

/** base64url sem preenchimento (o formato do WebAuthn e do protocolo guardião). */
object B64 {
    fun enc(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)

    fun dec(s: String): ByteArray = Base64.getUrlDecoder().decode(s)
}
