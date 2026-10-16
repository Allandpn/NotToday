package com.allan.nottoday.remote

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

fun newEcKey(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

/** Passkey de mentira (software) para assinar comandos nos testes. */
/** `backupEligible` true imita uma passkey sincronizada (conta Google/Apple): a flag BE vai dentro do authenticatorData assinado. */
class FakePasskey(val rpId: String, val origin: String, val backupEligible: Boolean = false) {
    val key = newEcKey()
    val credId = ByteArray(32).also { SecureRandom().nextBytes(it) }
    val credIdB64: String get() = B64.enc(credId)
    val spkiB64: String get() = B64.enc(key.public.encoded)

    /** A mesma chave, mas o autenticador diz "posso ser copiado" (BE). */
    fun signWithBackupFlag(guardian: String, payload: ByteArray): Map<String, Any?> = sign(guardian, payload, true, forceBackup = true)

    fun sign(guardian: String, payload: ByteArray, uv: Boolean = true, forceBackup: Boolean = false): Map<String, Any?> {
        val authData = WebAuthn.sha256(rpId.toByteArray()) + byteArrayOf((0x01 or (if (uv) 0x04 else 0) or (if (backupEligible || forceBackup) 0x08 else 0)).toByte()) + ByteArray(4)
        val cd = Json.encode(mapOf("type" to "webauthn.get", "challenge" to B64.enc(Commands.challengeOf(payload)), "origin" to origin))
            .toByteArray()
        val sig = Signature.getInstance("SHA256withECDSA").run {
            initSign(key.private)
            update(authData)
            update(WebAuthn.sha256(cd))
            sign()
        }
        return mapOf("guardian" to guardian, "cred" to credIdB64, "authenticatorData" to B64.enc(authData),
            "clientDataJSON" to B64.enc(cd), "signature" to B64.enc(sig))
    }
}

fun payloadOf(dev: String, guardian: String, ctr: Long, type: String, args: Map<String, Any?> = emptyMap()): ByteArray =
    Json.encode(mapOf("v" to 1, "dev" to dev, "guardian" to guardian, "ctr" to ctr, "type" to type, "args" to args)).toByteArray()

fun envelopeOf(payload: ByteArray, vararg sigs: Map<String, Any?>): Map<String, Any?> =
    mapOf("payload" to B64.enc(payload), "sigs" to sigs.toList())

/** Sela o PIN para a chave do aparelho, como o navegador faz (ECDH efêmero + HKDF + AES-GCM). */
fun sealPin(devicePublic: PublicKey, plaintext: String, aad: ByteArray): Map<String, Any?> {
    val eph = newEcKey()
    val shared = KeyAgreement.getInstance("ECDH").run { init(eph.private); doPhase(devicePublic, true); generateSecret() }
    val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
    val ct = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(PinBox.hkdf(shared), "AES"), GCMParameterSpec(128, nonce))
        updateAAD(aad)
        doFinal(plaintext.toByteArray())
    }
    val w = (eph.public as ECPublicKey).w
    fun pad(b: java.math.BigInteger): ByteArray = b.toByteArray().let { raw ->
        val trimmed = if (raw.size > 32) raw.copyOfRange(raw.size - 32, raw.size) else raw
        ByteArray(32 - trimmed.size) + trimmed
    }
    return mapOf("epk" to B64.enc(byteArrayOf(4) + pad(w.affineX) + pad(w.affineY)), "nonce" to B64.enc(nonce), "ct" to B64.enc(ct))
}
