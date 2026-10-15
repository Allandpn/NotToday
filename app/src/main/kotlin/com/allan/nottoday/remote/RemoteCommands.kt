package com.allan.nottoday.remote

import android.content.Context
import com.allan.nottoday.Approved
import com.allan.nottoday.Clocks
import com.allan.nottoday.Config
import com.allan.nottoday.Gate
import com.allan.nottoday.Maintenance
import com.allan.nottoday.Pending
import com.allan.nottoday.PendingStore
import com.allan.nottoday.PinStore
import com.allan.nottoday.PolicyManager

/**
 * Executa comandos já verificados dos guardiões pelos mesmos caminhos da tela (o canal remoto nunca é mais poderoso
 * que o PIN): configuração passa pelo [Gate] (afrouxar espera o atraso), aprovar app e endurecer valem na hora.
 */
class RemoteCommands(
    private val context: Context,
    private val store: RemoteStore,
    private val consumer: Consumer,
    /** Chave privada do aparelho (Android Keystore); os testes injetam uma chave de software. */
    private val devicePrivateKey: () -> java.security.PrivateKey = { DeviceKey.privateKey() },
) {
    class Result(val type: String, val ok: Boolean, val msg: String) {
        fun toMap(): Map<String, Any?> = mapOf("type" to type, "ok" to ok, "msg" to msg.take(200))
    }

    private val config = Config(context)
    private val policy = PolicyManager(context)
    private val pendings = PendingStore(context)
    private val approved = Approved(context)
    private val pins = PinStore(context)
    private val devPins = PinStore(context, PolicyManager.DEV_PIN)
    private val maintenance = Maintenance(context)

    fun handle(env: Map<String, Any?>): Result {
        val v = try {
            consumer.accept(env)
        } catch (e: CommandException) {
            return Result("?", false, "recusado: ${e.message}")
        }
        store.saveConsumer(consumer) // contador consumido ANTES de executar: no máximo uma execução por comando
        return try {
            Result(v.cmd.type, true, apply(v.cmd))
        } catch (e: CommandException) {
            Result(v.cmd.type, false, e.message ?: "falhou")
        } catch (e: Exception) {
            Result(v.cmd.type, false, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun apply(cmd: Command): String {
        val a = cmd.args
        return when (cmd.type) {
            "approve_app" -> {
                val pkg = pkg(a)
                approved.addAll(listOf(pkg))
                policy.quickSync()
                "app aprovado: $pkg"
            }
            "revoke_app" -> {
                val pkg = pkg(a)
                approved.remove(pkg)
                policy.quickSync()
                "aprovação revogada: $pkg"
            }
            "set_config" -> setConfig(a["patch"] as? Map<*, *> ?: throw CommandException("patch ausente"))
            "set_dev_policy" -> setConfig(
                buildMap {
                    a["windows"]?.let { put("dev_windows_per_day", it) }
                    a["seconds"]?.let { put("dev_window_seconds", it) }
                    a["reboot"]?.let { put("dev_reboot", it) }
                },
            )
            "confirm" -> confirm(kind(a))
            "cancel" -> {
                val k = kind(a)
                if (pendings.get(k) == null) throw CommandException("não há pedido $k")
                pendings.clear(k)
                "pedido $k cancelado"
            }
            "request_maintenance" -> {
                if (maintenance.remainingMs() > 0) throw CommandException("janela de manutenção já ativa")
                if (config.unlockDelayMin > 0) {
                    pendings.put(Pending.Kind.MAINTENANCE, emptyMap(), config.unlockDelayMin * 60_000L)
                    "janela de manutenção pedida (espera ${config.unlockDelayMin} min)"
                } else {
                    maintenance.start(Maintenance.DEFAULT_MINUTES)
                    policy.apply()
                    "janela de manutenção aberta"
                }
            }
            "set_pin" -> {
                val pin = openPin(cmd)
                if (devPins.isSet && devPins.matches(pin)) throw CommandException("o PIN não pode ser igual ao de desenvolvedor")
                pins.set(pin)
                "PIN local definido"
            }
            "set_dev_pin" -> {
                val pin = openPin(cmd)
                if (pins.isSet && pins.matches(pin)) throw CommandException("o PIN de desenvolvedor não pode ser igual ao principal")
                devPins.set(pin)
                "PIN de desenvolvedor definido"
            }
            "activate" -> {
                store.activated = true
                "aparelho ativado pelo guardião"
            }
            Commands.T_ADD_GUARDIAN, Commands.T_ADD_CREDENTIAL -> {
                consumer.applyTrust(cmd)
                store.saveConsumer(consumer)
                "confiança atualizada (${cmd.type})"
            }
            Commands.T_REMOVE_GUARDIAN -> removeGuardian(cmd)
            else -> throw CommandException("comando desconhecido: ${cmd.type}")
        }
    }

    private fun pkg(a: Map<String, Any?>): String {
        val p = a["pkg"] as? String ?: throw CommandException("pkg ausente")
        if (!PKG.matches(p)) throw CommandException("nome de pacote inválido")
        return p
    }

    private fun kind(a: Map<String, Any?>): Pending.Kind {
        val k = a["kind"] as? String ?: throw CommandException("kind ausente")
        return Pending.Kind.entries.firstOrNull { it.name == k } ?: throw CommandException("kind inválido")
    }

    private fun openPin(cmd: Command): String {
        @Suppress("UNCHECKED_CAST")
        val box = cmd.args["box"] as? Map<String, Any?> ?: throw CommandException("caixa do PIN ausente")
        val pin = try {
            String(PinBox.open(devicePrivateKey(), box, PinBox.aad(cmd.type, consumer.dev)), Charsets.UTF_8)
        } catch (e: CommandException) {
            throw e
        } catch (e: Exception) {
            throw CommandException("não foi possível abrir a caixa do PIN")
        }
        if (!PIN.matches(pin)) throw CommandException("o PIN precisa ter de ${PinStore.MIN_LENGTH} a 32 dígitos")
        return pin
    }

    private fun gateOn() = policy.isDeviceOwner() && config.unlockDelayMin > 0

    private fun setConfig(patch: Map<*, *>): String {
        if (patch.isEmpty() || patch.size > 40) throw CommandException("patch vazio ou grande demais")
        val old = config.snapshot()
        val new = LinkedHashMap(old)
        for ((k, raw) in patch) {
            val key = k as? String ?: throw CommandException("chave inválida")
            if (key !in old) throw CommandException("chave desconhecida: $key")
            val text = when (raw) {
                is String -> raw
                is Boolean, is Long -> raw.toString()
                else -> throw CommandException("valor inválido para $key")
            }
            new[key] = Config.normalize(key, text) ?: throw CommandException("valor inválido para $key")
        }
        val split = if (gateOn()) Gate.split(old, new) else Gate.Split(new.filter { old[it.key] != it.value }, emptyMap())
        config.applySnapshot(split.immediate)
        if (split.delayed.isNotEmpty()) pendings.put(Pending.Kind.SETTINGS, split.delayed, config.unlockDelayMin * 60_000L)
        policy.apply()
        return "${split.immediate.size} aplicada(s), ${split.delayed.size} em espera"
    }

    private fun confirm(kind: Pending.Kind): String {
        val p = pendings.get(kind) ?: throw CommandException("não há pedido $kind")
        when (val st = p.status(Clocks.now(context))) {
            is Pending.Status.Waiting -> throw CommandException("ainda em espera (${(st.remainingMs + 59_999) / 60_000} min)")
            Pending.Status.Expired -> {
                pendings.clear(kind)
                throw CommandException("o pedido expirou")
            }
            is Pending.Status.Ready -> {}
        }
        when (kind) {
            Pending.Kind.SETTINGS -> {
                config.applySnapshot(p.payload)
                pendings.clear(kind)
                policy.apply()
            }
            Pending.Kind.MAINTENANCE -> {
                pendings.clear(kind)
                maintenance.start(Maintenance.DEFAULT_MINUTES)
                policy.apply()
            }
            Pending.Kind.RELEASE -> {
                pendings.clear(kind)
                policy.releaseDeviceOwner() // também desfaz o pareamento e para o canal
            }
            Pending.Kind.GUARDIAN -> {
                val id = p.payload["id"] ?: throw CommandException("pedido sem guardião")
                consumer.applyTrust(Command(consumer.dev, "", 0, Commands.T_REMOVE_GUARDIAN, mapOf("id" to id)))
                store.saveConsumer(consumer)
                pendings.clear(kind)
            }
        }
        return "pedido $kind confirmado"
    }

    private fun removeGuardian(cmd: Command): String {
        val id = cmd.args["id"] as? String ?: throw CommandException("id ausente")
        val target = consumer.trust.guardians[id] ?: throw CommandException("guardião inexistente")
        if (consumer.trust.admins() == setOf(id)) throw CommandException("não remove o último administrador")
        // Tirar um guardião de domínios só reduz quem manda: vale na hora. Tirar um administrador passa pelo atraso.
        if (!gateOn() || target.role != Commands.ROLE_ADMIN) {
            consumer.applyTrust(cmd)
            store.saveConsumer(consumer)
            return "guardião removido: $id"
        }
        pendings.put(Pending.Kind.GUARDIAN, mapOf("id" to id), config.unlockDelayMin * 60_000L)
        return "remoção do guardião $id em espera (${config.unlockDelayMin} min); confirme depois"
    }

    private companion object {
        val PKG = Regex("^[A-Za-z0-9._]{1,200}$")
        val PIN = Regex("^[0-9]{${PinStore.MIN_LENGTH},32}$")
    }
}
