package com.allan.nottoday.remote

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Abre a caixa do PIN: ECDH P-256 + HKDF-SHA256 + AES-256-GCM. Formato em protocol/SPEC.md. */
object PinBox {
    private val INFO = "guardian/pinbox/v1".toByteArray(Charsets.UTF_8)

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }

    /** HKDF-SHA256 (RFC 5869) com salt vazio (= zeros) e um único bloco de saída (32 bytes). */
    fun hkdf(ikm: ByteArray): ByteArray = hmac(hmac(ByteArray(32), ikm), INFO + byteArrayOf(1))

    fun aad(type: String, dev: String): ByteArray = "guardian/$type/$dev".toByteArray(Charsets.UTF_8)

    /** `priv` pode ser uma chave do Android Keystore (ECDH feito dentro do Keystore). */
    fun open(priv: PrivateKey, box: Map<String, Any?>, aad: ByteArray): ByteArray {
        val epk = B64.dec(box["epk"] as? String ?: throw CommandException("caixa sem epk"))
        val nonce = B64.dec(box["nonce"] as? String ?: throw CommandException("caixa sem nonce"))
        val ct = B64.dec(box["ct"] as? String ?: throw CommandException("caixa sem ct"))
        if (epk.size != 65 || epk[0].toInt() != 4 || nonce.size != 12) throw CommandException("caixa malformada")
        val params = AlgorithmParameters.getInstance("EC").run {
            init(ECGenParameterSpec("secp256r1"))
            getParameterSpec(ECParameterSpec::class.java)
        }
        val point = ECPoint(BigInteger(1, epk.copyOfRange(1, 33)), BigInteger(1, epk.copyOfRange(33, 65)))
        val pub = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, params))
        val shared = KeyAgreement.getInstance("ECDH").run { init(priv); doPhase(pub, true); generateSecret() }
        val key = SecretKeySpec(hkdf(shared), "AES")
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
            updateAAD(aad)
            doFinal(ct)
        }
    }
}
