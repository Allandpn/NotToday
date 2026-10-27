package com.allan.nottoday.remote

/** Comandos assinados pelos guardiões e o estado de confiança do aparelho. Espelha src/guardian/protocol/commands.py. */
class CommandException(message: String) : Exception(message)

/**
 * Horário em que os comandos de um guardião valem: dias (segunda = 0), minutos do dia [from, to) e o deslocamento fixo do fuso.
 * Quem consome o comando confere com o PRÓPRIO relógio (não o do navegador). Espelha `check_window`/`in_window` do Python.
 */
class Window(val days: List<Int>, val from: Int, val to: Int, val utcOffsetMin: Int) {
    fun contains(nowMs: Long): Boolean {
        val t = java.time.Instant.ofEpochMilli(nowMs).plusSeconds(utcOffsetMin * 60L).atZone(java.time.ZoneOffset.UTC)
        val min = t.hour * 60 + t.minute
        return (t.dayOfWeek.value - 1) in days && min >= from && min < to
    }

    fun toJson(): Map<String, Any?> = linkedMapOf("days" to days, "from" to from, "to" to to, "utc_offset_min" to utcOffsetMin)

    companion object {
        fun parse(v: Any?): Window? {
            if (v == null) return null
            val m = v as? Map<*, *> ?: throw CommandException("janela de horário inválida")
            val days = (m["days"] as? List<*>)?.map { (it as? Long)?.toInt() ?: throw CommandException("janela de horário inválida") }
            val from = (m["from"] as? Long)?.toInt()
            val to = (m["to"] as? Long)?.toInt()
            val off = (m["utc_offset_min"] as? Long)?.toInt()
            if (days.isNullOrEmpty() || days.any { it !in 0..6 } || from == null || to == null || off == null ||
                from < 0 || from >= to || to > 1440 || off < -840 || off > 840
            ) throw CommandException("janela de horário inválida")
            return Window(days.distinct().sorted(), from, to, off)
        }
    }
}

/**
 * `role`: "master", "admin" ou "domains" (veja docs/PAPEIS.md no guardian). Pareamentos antigos, sem papel, são de guardiões-mor.
 * `allowSync` false: só vale passkey presa ao aparelho (flag BE ausente). `window`: horário de validade. Os dois são definidos
 * quando o guardião é criado e não mudam.
 */
class Guardian(
    val name: String,
    val creds: MutableMap<String, String> = LinkedHashMap(),
    var role: String = Commands.ROLE_ADMIN,
    val allowSync: Boolean = true,
    val window: Window? = null,
)

class Trust(val rpId: String, val origin: String, val guardians: MutableMap<String, Guardian> = LinkedHashMap()) {
    /** Guardiões com poderes de guardião-mor (inclui o mestre). */
    fun admins(): Set<String> = guardians.filterValues { it.role in Commands.ADMINISH }.keys

    fun masters(): Set<String> = guardians.filterValues { it.role == Commands.ROLE_MASTER }.keys

    fun toJson(): Map<String, Any?> = linkedMapOf(
        "rp_id" to rpId, "origin" to origin,
        "guardians" to guardians.mapValues { (_, g) ->
            linkedMapOf("name" to g.name, "creds" to g.creds, "role" to g.role, "allow_sync" to g.allowSync, "window" to g.window?.toJson())
        },
    )

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromJson(d: Map<String, Any?>): Trust {
            val gs = LinkedHashMap<String, Guardian>()
            for ((id, v) in (d["guardians"] as? Map<String, Any?> ?: emptyMap())) {
                val m = v as? Map<String, Any?> ?: throw CommandException("guardião inválido")
                val creds = LinkedHashMap<String, String>()
                for ((c, s) in (m["creds"] as? Map<String, Any?> ?: emptyMap())) creds[c] = s as? String ?: throw CommandException("credencial inválida")
                gs[id] = Guardian(
                    m["name"] as? String ?: "", creds, Commands.role(m["role"] ?: Commands.ROLE_ADMIN),
                    Commands.bool(m["allow_sync"] ?: true), Window.parse(m["window"]),
                )
            }
            return Trust(
                d["rp_id"] as? String ?: throw CommandException("rp_id ausente"),
                d["origin"] as? String ?: throw CommandException("origin ausente"), gs,
            )
        }
    }
}

class Command(val dev: String, val guardian: String, val ctr: Long, val type: String, val args: Map<String, Any?>)

class InstallArgs(val pkg: String, val sha256: String, val size: Long)

class Verified(val cmd: Command, val payload: ByteArray, val signers: Set<String>)

object Commands {
    const val VERSION = 1L
    const val MAX_PAYLOAD = 8 * 1024
    const val T_ADD_GUARDIAN = "add_guardian"
    const val T_ADD_CREDENTIAL = "add_credential"
    const val T_REMOVE_GUARDIAN = "remove_guardian"
    const val T_REMOVE_CREDENTIAL = "remove_credential"
    const val T_SET_ROLE = "set_role"
    const val T_INSTALL_APP = "install_app"
    const val FLAG_BE = 0x08 // backup eligible: a passkey pode ser copiada para a nuvem
    val TRUST_TYPES = setOf(T_ADD_GUARDIAN, T_ADD_CREDENTIAL, T_REMOVE_GUARDIAN, T_REMOVE_CREDENTIAL, T_SET_ROLE)

    /** Dois guardiões-mor, enquanto não existe mestre (modo antigo). Com o mestre como autor, basta ele. set_role não entra: o primeiro mestre nasce com uma assinatura. */
    val TWO_SIG_TYPES = setOf(T_REMOVE_GUARDIAN, T_REMOVE_CREDENTIAL)
    const val ROLE_MASTER = "master" // inclui e remove guardiões (inclusive outro mestre), sozinho; tem tudo do guardião-mor
    const val ROLE_ADMIN = "admin"
    const val ROLE_DOMAINS = "domains"
    val ROLES = setOf(ROLE_MASTER, ROLE_ADMIN, ROLE_DOMAINS)
    val ADMINISH = setOf(ROLE_MASTER, ROLE_ADMIN)

    /** Só o guardião-mestre assina estes (instalar software no aparelho é o poder mais alto). */
    val MASTER_TYPES = setOf(T_INSTALL_APP)
    val DOMAIN_TYPES = setOf("pihole_allow", "pihole_deny", "pihole_remove", "pihole_batch")

    /** O que um guardião comum pode mandar: domínios e aprovar/revogar app (regras, PIN e pessoas são do guardião-mor). */
    val GUARDIAN_TYPES = DOMAIN_TYPES + setOf("approve_app", "revoke_app")

    private val INSTALL_KEYS = setOf("pkg", "sha256", "size")
    private val INSTALL_PKG = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    private val SHA256_HEX = Regex("^[0-9a-f]{64}$")
    const val MAX_APK_BYTES = 150L shl 20 // 150 MiB, o mesmo limite do Pi

    /** Argumentos de `install_app`; qualquer coisa fora do formato é recusada (espelha `check_install_args` do Python). */
    fun checkInstallArgs(a: Map<String, Any?>): InstallArgs {
        if (a.keys.any { it !in INSTALL_KEYS }) throw CommandException("argumentos de install_app inválidos")
        val pkg = a["pkg"] as? String
        if (pkg == null || pkg.length > 200 || !INSTALL_PKG.matches(pkg)) throw CommandException("nome de pacote inválido")
        val sha = a["sha256"] as? String
        if (sha == null || !SHA256_HEX.matches(sha)) throw CommandException("sha256 inválido")
        val size = a["size"] as? Long
        if (size == null || size < 1 || size > MAX_APK_BYTES) throw CommandException("tamanho inválido")
        return InstallArgs(pkg, sha, size)
    }

    fun bool(b: Any?): Boolean = b as? Boolean ?: throw CommandException("valor lógico inválido")

    fun role(r: Any?): String = (r as? String)?.takeIf { it in ROLES } ?: throw CommandException("papel inválido")

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
                val authData = B64.dec(m["authenticatorData"] as String)
                WebAuthn.verifyAssertion(
                    spki, authData, B64.dec(m["clientDataJSON"] as String),
                    B64.dec(m["signature"] as String), challenge, trust.rpId, trust.origin,
                )
                // A flag BE está dentro do que foi assinado: não dá para esconder que a passkey é sincronizável.
                if (!g.allowSync && (authData[32].toInt() and FLAG_BE) != 0) throw CommandException("passkey sincronizável não vale para este guardião")
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

    /** Quantos guardiões-mor precisam assinar: se o autor é o mestre, ele basta; senão vale o modo antigo (dois, se houver dois). */
    fun requiredSigners(trust: Trust, cmd: Command): Int {
        if (trust.guardians[cmd.guardian]?.role == ROLE_MASTER) return 1
        return if (cmd.type in TWO_SIG_TYPES && trust.admins().size >= 2) 2 else 1
    }

    /**
     * O autor pode mandar este comando? Guardião: domínios, aprovar app e passkey nova para si mesmo. A janela de horário do autor
     * vale na hora em que este aparelho recebe o comando, pelo relógio dele.
     */
    fun authorize(trust: Trust, cmd: Command, signers: Set<String>, nowMs: Long) {
        val author = trust.guardians[cmd.guardian] ?: throw CommandException("autor desconhecido")
        if (author.window?.contains(nowMs) == false) throw CommandException("fora da janela de horário deste guardião")
        if (cmd.type in GUARDIAN_TYPES) return
        if (cmd.type == T_ADD_CREDENTIAL && cmd.args["id"] == cmd.guardian) return // qualquer guardião inclui passkey para si mesmo
        if (cmd.type in MASTER_TYPES) {
            if (author.role != ROLE_MASTER) throw CommandException("só o guardião-mestre pode mandar este comando")
            if (cmd.type == T_INSTALL_APP) checkInstallArgs(cmd.args)
            return
        }
        if (author.role !in ADMINISH) throw CommandException("o guardião não pode mandar este comando")
        if (cmd.type in TRUST_TYPES && author.role != ROLE_MASTER) {
            val target = trust.guardians[cmd.args["id"]]?.role
            val promotion = cmd.type == T_SET_ROLE && cmd.args["role"] == ROLE_MASTER
            // Sem mestre: modo antigo, e a única mudança de papel é promover um guardião-mor a mestre. Com mestre, a promoção que
            // ainda está em andamento em outros destinos continua valendo (o alvo já é mestre aqui).
            if (trust.masters().isNotEmpty()) {
                if (!(promotion && target == ROLE_MASTER)) throw CommandException("só o guardião-mestre inclui, remove ou muda guardiões")
            } else if (cmd.type == T_SET_ROLE && !(promotion && target == ROLE_ADMIN)) {
                throw CommandException("sem mestre, só dá para promover um guardião-mor a mestre")
            }
        }
        if ((signers intersect trust.admins()).size < requiredSigners(trust, cmd)) throw CommandException("faltam assinaturas de outro guardião-mor")
    }

    /** Criptografia e quórum. Contador e destino são conferidos pelo [Consumer]. */
    fun verifyEnvelope(trust: Trust, env: Map<String, Any?>, nowMs: Long = System.currentTimeMillis()): Verified {
        val payload = try {
            B64.dec(env["payload"] as String)
        } catch (e: Exception) {
            throw CommandException("payload inválido")
        }
        val cmd = parsePayload(payload)
        val signers = verifySigs(trust, payload, env["sigs"])
        if (cmd.guardian !in signers) throw CommandException("o autor do comando não assinou")
        authorize(trust, cmd, signers, nowMs)
        return Verified(cmd, payload, signers)
    }
}

/** Estado de quem recebe comandos: confiança fixada, contador por guardião e o id do próprio aparelho. */
class Consumer(val dev: String, val trust: Trust, val lastCtr: MutableMap<String, Long> = LinkedHashMap()) {
    fun accept(env: Map<String, Any?>, nowMs: Long = System.currentTimeMillis()): Verified {
        val v = Commands.verifyEnvelope(trust, env, nowMs)
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
                trust.guardians[id] = Guardian(
                    s("name").take(64), linkedMapOf(s("cred") to s("spki")), Commands.role(a["role"]),
                    Commands.bool(a["allow_sync"]), Window.parse(a["window"]),
                )
            }
            Commands.T_ADD_CREDENTIAL -> {
                (trust.guardians[s("id")] ?: throw CommandException("guardião inexistente")).creds[s("cred")] = s("spki")
            }
            Commands.T_REMOVE_CREDENTIAL -> {
                val g = trust.guardians[s("id")] ?: throw CommandException("guardião inexistente")
                if (s("cred") !in g.creds) throw CommandException("passkey inexistente")
                if (g.creds.size <= 1) throw CommandException("não remove a última passkey: remova o guardião")
                g.creds.remove(s("cred"))
            }
            Commands.T_REMOVE_GUARDIAN -> {
                val id = s("id")
                if (id !in trust.guardians) throw CommandException("guardião inexistente")
                if (trust.admins() == setOf(id)) throw CommandException("não remove o último guardião-mor")
                if (trust.masters() == setOf(id)) throw CommandException("não remove o último mestre")
                trust.guardians.remove(id)
                lastCtr.remove(id)
            }
            Commands.T_SET_ROLE -> {
                val id = s("id")
                val g = trust.guardians[id] ?: throw CommandException("guardião inexistente")
                val role = Commands.role(a["role"])
                if (trust.masters() == setOf(id) && role != Commands.ROLE_MASTER) throw CommandException("não rebaixa o último mestre")
                g.role = role
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
