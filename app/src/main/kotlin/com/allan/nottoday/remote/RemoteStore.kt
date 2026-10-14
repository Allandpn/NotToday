package com.allan.nottoday.remote

import android.annotation.SuppressLint
import android.content.Context

/** Estado do pareamento com o gateway: URL fixada, confiança nos guardiões, contadores e cursor da fila. */
class RemoteStore(context: Context) {
    private val prefs = context.createDeviceProtectedStorageContext().getSharedPreferences("remote", Context.MODE_PRIVATE)

    var gateway: String?
        get() = prefs.getString("gateway", null)
        set(v) = prefs.edit().putString("gateway", v).apply()

    var registered: Boolean
        get() = prefs.getBoolean("registered", false)
        set(v) = prefs.edit().putBoolean("registered", v).apply()

    /** O guardião enviou `activate` (conferiu o fingerprint). */
    var activated: Boolean
        get() = prefs.getBoolean("activated", false)
        set(v) = prefs.edit().putBoolean("activated", v).apply()

    /** Último `seq` de comando já visto no gateway. */
    var after: Long
        get() = prefs.getLong("after", 0)
        set(v) = prefs.edit().putLong("after", v).apply()

    var fingerprint: String?
        get() = prefs.getString("fingerprint", null)
        set(v) = prefs.edit().putString("fingerprint", v).apply()

    var lastContact: Long
        get() = prefs.getLong("last_contact", 0)
        set(v) = prefs.edit().putLong("last_contact", v).apply()

    val isPaired: Boolean get() = registered && prefs.contains("consumer")

    fun consumer(): Consumer? = prefs.getString("consumer", null)?.let {
        try {
            Consumer.fromJson(Json.parseObject(it))
        } catch (_: Exception) {
            null
        }
    }

    /** Grava na hora (commit): o contador consumido precisa estar em disco antes de o comando ser executado. */
    @SuppressLint("ApplySharedPref")
    fun saveConsumer(c: Consumer) {
        prefs.edit().putString("consumer", Json.encode(c.toJson())).commit()
    }

    @SuppressLint("ApplySharedPref")
    fun clear() {
        prefs.edit().clear().commit()
    }
}
