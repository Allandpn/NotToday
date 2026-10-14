package com.allan.nottoday.remote

import android.content.Context
import java.net.URI

/**
 * Pareamento com o gateway a partir da string gerada no site do guardião: fixa a URL, o rpId e os guardiões em quem
 * este aparelho vai confiar, registra a chave do aparelho e mostra o fingerprint para o guardião conferir por voz.
 */
object Pairing {
    class Parsed(val url: String, val token: String, val trust: Trust)

    fun parse(text: String, allowInsecure: Boolean = false): Parsed {
        try {
            val d = Json.parseObject(String(B64.dec(text.trim()), Charsets.UTF_8))
            if (d["v"] != 1L) throw CommandException("versão não suportada")
            val url = (d["url"] as String).trimEnd('/')
            val rp = d["rp"] as String
            val uri = URI(url)
            if ((uri.scheme != "https" && !(allowInsecure && uri.scheme == "http")) || uri.host != rp || !uri.rawPath.isNullOrEmpty() || uri.rawQuery != null) {
                throw CommandException("o endereço do gateway precisa ser https://<rpId> sem caminho")
            }
            val trust = Trust.fromJson(mapOf("rp_id" to rp, "origin" to url, "guardians" to (d["guardians"] ?: emptyMap<String, Any?>())))
            if (trust.guardians.isEmpty()) throw CommandException("a string não traz guardiões")
            return Parsed(url, d["token"] as String, trust)
        } catch (e: CommandException) {
            throw e
        } catch (e: Exception) {
            throw CommandException("string de pareamento inválida")
        }
    }

    /** Registra o aparelho (rede). Devolve o fingerprint. Chamar fora da thread principal. */
    fun register(
        context: Context, text: String, name: String,
        signer: DeviceSigner = DeviceKey.also { it.ensure() }, allowInsecure: Boolean = false,
    ): String {
        val p = parse(text, allowInsecure)
        val spki = signer.spki()
        GatewayApi(p.url, signer, allowInsecure).call(
            "POST", "/v1/devices/register",
            mapOf("pairing" to p.token, "pubkey" to B64.enc(spki), "name" to name.trim().ifEmpty { "Aparelho" }.take(64), "kind" to "device"),
        )
        val store = RemoteStore(context)
        store.saveConsumer(Consumer(DevAuth.deviceId(spki), p.trust))
        store.gateway = p.url
        store.after = 0
        store.activated = false
        store.fingerprint = DevAuth.fingerprint(spki)
        store.registered = true
        return DevAuth.fingerprint(spki)
    }

    /** Desfaz o pareamento neste aparelho (a chave é apagada; o gateway ainda lista o aparelho até o guardião esquecê-lo). */
    fun unpair(context: Context) {
        RemoteChannel.stop()
        RemoteStore(context).clear()
        DeviceKey.delete()
    }
}
