package com.allan.nottoday.remote

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * Par P-256 do aparelho no Android Keystore (não exportável). Assina as requisições ao gateway e os relatórios,
 * e faz o ECDH que abre a caixa do PIN. O id do aparelho é derivado da chave pública ([DevAuth.deviceId]).
 */
/** O que o gateway e o pareamento precisam da chave do aparelho (os testes usam uma chave de software). */
interface DeviceSigner {
    fun spki(): ByteArray

    fun sign(data: ByteArray): ByteArray
}

object DeviceKey : DeviceSigner {
    private const val ALIAS = "nottoday_remote_v1"

    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Synchronized
    fun ensure() {
        if (store().containsAlias(ALIAS)) return
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_AGREE_KEY)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").run {
            initialize(spec)
            generateKeyPair()
        }
    }

    fun exists(): Boolean = store().containsAlias(ALIAS)

    /** Chave pública em SPKI/DER (X.509), o formato do protocolo. */
    override fun spki(): ByteArray = store().getCertificate(ALIAS).publicKey.encoded

    fun privateKey(): PrivateKey = store().getKey(ALIAS, null) as PrivateKey

    /** ECDSA-SHA256 em DER. */
    override fun sign(data: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(privateKey())
        update(data)
        sign()
    }

    fun delete() {
        try {
            store().deleteEntry(ALIAS)
        } catch (_: Exception) {
        }
    }
}
