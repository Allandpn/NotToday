package com.allan.nottoday

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** PIN guardado só como hash PBKDF2 + sal, com atraso crescente após erros. */
class PinStore(context: Context, name: String = "pin") {
    private val prefs = context.createDeviceProtectedStorageContext()
        .getSharedPreferences(name, Context.MODE_PRIVATE)

    val isSet get() = prefs.contains("hash")

    /**
     * Janela única para definir o 1º PIN quando o Device Owner foi ativado sem PIN. Fora dela, Device Owner
     * sem PIN (ex.: dados do app apagados via ADB) é estado travado: ninguém consegue criar um PIN novo.
     */
    val firstPinAllowedAsOwner get() = prefs.getBoolean("grace", false)

    fun openFirstPinWindow() {
        if (!isSet) prefs.edit().putBoolean("grace", true).apply()
    }

    fun set(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.edit()
            .putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("hash", Base64.encodeToString(hash(pin, salt), Base64.NO_WRAP))
            .putInt("fails", 0)
            .putLong("locked_until", 0)
            .remove("grace")
            .apply()
    }

    /** Milissegundos restantes de bloqueio (0 = livre). */
    fun lockedForMs(): Long = (prefs.getLong("locked_until", 0) - System.currentTimeMillis()).coerceAtLeast(0)

    fun verify(pin: String): Boolean {
        if (lockedForMs() > 0) return false
        val salt = Base64.decode(prefs.getString("salt", null) ?: return false, Base64.NO_WRAP)
        val expected = Base64.decode(prefs.getString("hash", null) ?: return false, Base64.NO_WRAP)
        val ok = MessageDigest.isEqual(expected, hash(pin, salt))
        val edit = prefs.edit()
        if (ok) {
            edit.putInt("fails", 0).putLong("locked_until", 0)
        } else {
            val fails = prefs.getInt("fails", 0) + 1
            edit.putInt("fails", fails)
            if (fails >= FREE_ATTEMPTS) {
                val minutes = (BASE_LOCK_MIN shl (fails - FREE_ATTEMPTS).coerceAtMost(7)).coerceAtMost(MAX_LOCK_MIN)
                edit.putLong("locked_until", System.currentTimeMillis() + minutes * 60_000L)
            }
        }
        edit.apply()
        return ok
    }

    /**
     * Compara sem contar tentativa nem bloquear. Só para o canal remoto impedir que o PIN principal e o de
     * desenvolvedor sejam iguais (a tela usa [verify]).
     */
    fun matches(pin: String): Boolean {
        val salt = Base64.decode(prefs.getString("salt", null) ?: return false, Base64.NO_WRAP)
        val expected = Base64.decode(prefs.getString("hash", null) ?: return false, Base64.NO_WRAP)
        return MessageDigest.isEqual(expected, hash(pin, salt))
    }

    fun clear() = prefs.edit().clear().apply()

    private fun hash(pin: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256))
            .encoded

    companion object {
        const val MIN_LENGTH = 6
        private const val ITERATIONS = 160_000
        private const val FREE_ATTEMPTS = 5
        private const val BASE_LOCK_MIN = 15L
        private const val MAX_LOCK_MIN = 24 * 60L
    }
}
