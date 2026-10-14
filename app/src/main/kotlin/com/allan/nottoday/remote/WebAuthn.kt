package com.allan.nottoday.remote

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Verificação de asserção de passkey (WebAuthn `get`, só ES256). Mesmo algoritmo do gateway e do executor
 * (src/guardian/protocol/webauthn.py): origin, challenge, rpIdHash, flags UP+UV e a assinatura sobre
 * authenticatorData ‖ SHA-256(clientDataJSON). O signCount é ignorado de propósito.
 */
object WebAuthn {
    class WebAuthnException(message: String) : Exception(message)

    private const val FLAG_UP = 0x01
    private const val FLAG_UV = 0x04

    fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

    fun verifyAssertion(
        spki: ByteArray, authenticatorData: ByteArray, clientDataJson: ByteArray, signature: ByteArray,
        challenge: ByteArray, rpId: String, origin: String,
    ) {
        val cd = try {
            Json.parseObject(String(clientDataJson, Charsets.UTF_8))
        } catch (e: Json.JsonException) {
            throw WebAuthnException("clientDataJSON inválido")
        }
        if (cd["type"] != "webauthn.get") throw WebAuthnException("tipo de cerimônia errado")
        if (cd["challenge"] != B64.enc(challenge)) throw WebAuthnException("challenge diferente")
        if (cd["origin"] != origin) throw WebAuthnException("origin diferente")
        if (cd["crossOrigin"] == true) throw WebAuthnException("crossOrigin não permitido")

        if (authenticatorData.size < 37) throw WebAuthnException("authenticatorData curto")
        val rpHash = sha256(rpId.toByteArray(Charsets.UTF_8))
        if (!MessageDigest.isEqual(authenticatorData.copyOfRange(0, 32), rpHash)) throw WebAuthnException("rpIdHash diferente")
        val flags = authenticatorData[32].toInt() and 0xFF
        if (flags and FLAG_UP == 0 || flags and FLAG_UV == 0) throw WebAuthnException("exige presença e verificação do usuário")

        val ok = try {
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(key)
                update(authenticatorData)
                update(sha256(clientDataJson))
                verify(signature)
            }
        } catch (e: Exception) {
            false
        }
        if (!ok) throw WebAuthnException("assinatura inválida")
    }
}
