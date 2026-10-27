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

    /** Ids dos pedidos de liberação ainda abertos (separados por vírgula): o canal avisa quando o guardião responde. */
    var watched: Set<Long>
        get() = (prefs.getString("watched", "") ?: "").split(",").mapNotNull { it.toLongOrNull() }.toSet()
        set(v) = prefs.edit().putString("watched", v.joinToString(",")).apply()

    /** "Limpar" na tela de bloqueados só esconde aqui: o log do guardião não muda. Guarda o `last` mais novo que a pessoa viu. */
    var blockedClearedAt: Long
        get() = prefs.getLong("blockedClearedAt", 0L)
        set(v) = prefs.edit().putLong("blockedClearedAt", v).apply()

    /** Pedidos respondidos com id até este já foram limpos da tela (os ids só crescem). */
    var requestsClearedId: Long
        get() = prefs.getLong("requestsClearedId", 0L)
        set(v) = prefs.edit().putLong("requestsClearedId", v).apply()

    /** Certificado (SHA-256 dos assinantes) visto na primeira instalação de cada app pelo canal; as próximas têm de ter o mesmo. */
    fun certPin(pkg: String): String? = prefs.getString("cert_$pkg", null)

    fun setCertPin(pkg: String, certs: String) = prefs.edit().putString("cert_$pkg", certs).apply()

    /** Atualização do próprio NotToday em andamento (versionCode|versionName): o processo morre na troca, a 1ª inicialização depois relata. */
    @SuppressLint("ApplySharedPref")
    fun setPendingSelfInstall(v: String?) {
        prefs.edit().apply { if (v == null) remove("self_install") else putString("self_install", v) }.commit()
    }

    fun pendingSelfInstall(): String? = prefs.getString("self_install", null)

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
