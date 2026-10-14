package com.allan.nottoday.remote

/** Comandos assinados pelos guardiões e o estado de confiança do aparelho. Espelha src/guardian/protocol/commands.py. */
class CommandException(message: String) : Exception(message)

class Guardian(val name: String, val creds: MutableMap<String, String> = LinkedHashMap())

class Trust(val rpId: String, val origin: String, val guardians: MutableMap<String, Guardian> = LinkedHashMap()) {
    fun toJson(): Map<String, Any?> = linkedMapOf(
        "rp_id" to rpId, "origin" to origin,
        "guardians" to guardians.mapValues { (_, g) -> linkedMapOf("name" to g.name, "creds" to g.creds) },
    )

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromJson(d: Map<String, Any?>): Trust {
            val gs = LinkedHashMap<String, Guardian>()
            for ((id, v) in (d["guardians"] as? Map<String, Any?> ?: emptyMap())) {
                val m = v as? Map<String, Any?> ?: throw CommandException("guardião inválido")
                val creds = LinkedHashMap<String, String>()
                for ((c, s) in (m["creds"] as? Map<String, Any?> ?: emptyMap())) creds[c] = s as? String ?: throw CommandException("credencial inválida")
                gs[id] = Guardian(m["name"] as? String ?: "", creds)
            }
            return Trust(
                d["rp_id"] as? String ?: throw CommandException("rp_id ausente"),
                d["origin"] as? String ?: throw CommandException("origin ausente"), gs,
            )
        }
    }
}

class Command(val dev: String, val guardian: String, val ctr: Long, val type: String, val args: Map<String, Any?>)

class Verified(val cmd: Command, val payload: ByteArray, val signers: Set<String>)

object Commands {
    const val VERSION = 1L
    const val MAX_PAYLOAD = 8 * 1024
    const val T_ADD_GUARDIAN = "add_guardian"
    const val T_ADD_CREDENTIAL = "add_credential"
    const val T_REMOVE_GUARDIAN = "remove_guardian"
    val TRUST_TYPES = setOf(T_ADD_GUARDIAN, T_ADD_CREDENTIAL, T_REMOVE_GUARDIAN)
    val TWO_SIG_TYPES = setOf(T_REMOVE_GUARDIAN)

    fun challengeOf(payload: ByteArray): ByteArray = WebAuthn.sha256(payload)

    @Suppress("UNCHECKED_CAST")
    fun parsePayload(payload: ByteArray): Command {
        if (payload.size > MAX_PAYLOAD) throw CommandException("comando grande demais")
        val m = try {
            Json.parseObject(String(payload, Charsets.UTF_8))
        } catch (e: Json.JsonException) {
            throw CommandException("comando não é JSON")
        }
        if (m["v"] != VERSION) throw CommandException("versão do comando não suportada")
        fun text(k: String) = m[k] as? String ?: throw CommandException("campo '$k' ausente ou inválido")
        val args = m["args"] as? Map<String, Any?> ?: throw CommandException("campo 'args' ausente ou inválido")
        val ctr = m["ctr"] as? Long
        if (ctr == null || ctr < 1) throw CommandException("contador inválido")
        return Command(text("dev"), text("guardian"), ctr, text("type"), args)
    }

    fun verifySigs(trust: Trust, payload: ByteArray, sigs: Any?): Set<String> {
        val list = sigs as? List<*>
        if (list == null || list.size !in 1..4) throw CommandException("assinaturas ausentes")
        val challenge = challengeOf(payload)
        val signers = HashSet<String>()
        for (s in list) {
            try {
                val m = s as Map<*, *>
                val gid = m["guardian"] as String
                val g = trust.guardians[gid] ?: throw CommandException("guardião desconhecido")
                val spki = B64.dec(g.creds[m["cred"] as String] ?: throw CommandException("credencial desconhecida"))
                WebAuthn.verifyAssertion(
                    spki, B64.dec(m["authenticatorData"] as String), B64.dec(m["clientDataJSON"] as String),
                    B64.dec(m["signature"] as String), challenge, trust.rpId, trust.origin,
                )
                signers.add(gid)
            } catch (e: CommandException) {
                throw CommandException("assinatura recusada: ${e.message}")
            } catch (e: WebAuthn.WebAuthnException) {
                throw CommandException("assinatura recusada: ${e.message}")
            } catch (e: Exception) {
                throw CommandException("assinatura recusada: malformada")
            }
        }
        return signers
    }

    fun requiredSigners(trust: Trust, type: String): Int = if (type in TWO_SIG_TYPES && trust.guardians.size >= 2) 2 else 1

    /** Criptografia e quórum. Contador e destino são conferidos pelo [Consumer]. */
    fun verifyEnvelope(trust: Trust, env: Map<String, Any?>): Verified {
        val payload = try {
            B64.dec(env["payload"] as String)
        } catch (e: Exception) {
            throw CommandException("payload inválido")
        }
        val cmd = parsePayload(payload)
        val signers = verifySigs(trust, payload, env["sigs"])
        if (cmd.guardian !in signers) throw CommandException("o autor do comando não assinou")
        if (signers.size < requiredSigners(trust, cmd.type)) throw CommandException("faltam assinaturas de outro guardião")
        return Verified(cmd, payload, signers)
    }
}

/** Estado de quem recebe comandos: confiança fixada, contador por guardião e o id do próprio aparelho. */
class Consumer(val dev: String, val trust: Trust, val lastCtr: MutableMap<String, Long> = LinkedHashMap()) {
    fun accept(env: Map<String, Any?>): Verified {
        val v = Commands.verifyEnvelope(trust, env)
        if (v.cmd.dev != dev) throw CommandException("comando de outro destino")
        if (v.cmd.ctr <= (lastCtr[v.cmd.guardian] ?: 0L)) throw CommandException("contador repetido (replay)")
        lastCtr[v.cmd.guardian] = v.cmd.ctr
        return v
    }

    /** add_guardian / add_credential / remove_guardian já aceitos (o atraso de remover é de quem chama). */
    fun applyTrust(cmd: Command) {
        val a = cmd.args
        fun s(k: String) = a[k] as? String ?: throw CommandException("argumento ausente: $k")
        when (cmd.type) {
            Commands.T_ADD_GUARDIAN -> {
                val id = s("id")
                if (id in trust.guardians) throw CommandException("guardião já existe")
                trust.guardians[id] = Guardian(s("name").take(64), linkedMapOf(s("cred") to s("spki")))
            }
            Commands.T_ADD_CREDENTIAL -> {
                (trust.guardians[s("id")] ?: throw CommandException("guardião inexistente")).creds[s("cred")] = s("spki")
            }
            Commands.T_REMOVE_GUARDIAN -> {
                val id = s("id")
                if (trust.guardians.size <= 1) throw CommandException("não remove o último guardião")
                if (trust.guardians.remove(id) == null) throw CommandException("guardião inexistente")
                lastCtr.remove(id)
            }
            else -> throw CommandException("não é comando de confiança")
        }
    }

    fun toJson(): Map<String, Any?> = linkedMapOf("dev" to dev, "trust" to trust.toJson(), "last_ctr" to lastCtr)

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromJson(d: Map<String, Any?>): Consumer {
            val ctr = LinkedHashMap<String, Long>()
            for ((k, v) in (d["last_ctr"] as? Map<String, Any?> ?: emptyMap())) ctr[k] = v as? Long ?: throw CommandException("contador inválido")
            return Consumer(
                d["dev"] as? String ?: throw CommandException("dev ausente"),
                Trust.fromJson(d["trust"] as? Map<String, Any?> ?: throw CommandException("trust ausente")), ctr,
            )
        }
    }
}
