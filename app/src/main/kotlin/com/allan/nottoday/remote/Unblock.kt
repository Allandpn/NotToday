package com.allan.nottoday.remote

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

data class BlockedItem(val domain: String, val own: Boolean, val list: String, val cname: Boolean, val count: Long, val last: Long)
data class RequestItem(val domain: String, val state: String, val ttlS: Long?)
data class UnblockRequest(val id: Long, val status: String, val items: List<RequestItem>)

/** Pedidos de liberação ao guardião: o que o Pi-hole bloqueou, o pedido e a resposta. Nada de texto livre. */
class UnblockApi(private val api: GatewayApi, private val dev: String) {
    fun blocked(onlyOwn: Boolean): List<BlockedItem> {
        val r = api.call("GET", "/v1/devices/$dev/blocked" + if (onlyOwn) "?src=own" else "")
        return (r["items"] as? List<*>).orEmpty().mapNotNull { m ->
            val x = m as? Map<*, *> ?: return@mapNotNull null
            BlockedItem(
                domain = x["domain"] as? String ?: return@mapNotNull null,
                own = x["src"] == "own",
                list = x["list"] as? String ?: "",
                cname = x["cname"] == true,
                count = x["count"] as? Long ?: 1L,
                last = x["last"] as? Long ?: 0L,
            )
        }
    }

    /** Devolve o id do pedido, ou null se todos os domínios já tinham pedido aberto. */
    fun request(domains: Collection<String>): Long? {
        val r = api.call("POST", "/v1/devices/$dev/requests", mapOf("kind" to "unblock", "domains" to domains.toList()))
        return r["id"] as? Long
    }

    fun requests(): List<UnblockRequest> = (api.call("GET", "/v1/devices/$dev/requests")["requests"] as? List<*>).orEmpty().mapNotNull { m ->
        val x = m as? Map<*, *> ?: return@mapNotNull null
        UnblockRequest(
            id = x["id"] as? Long ?: return@mapNotNull null,
            status = x["status"] as? String ?: "open",
            items = (x["items"] as? List<*>).orEmpty().mapNotNull { i ->
                val y = i as? Map<*, *> ?: return@mapNotNull null
                RequestItem(y["domain"] as? String ?: return@mapNotNull null, y["state"] as? String ?: "open", y["ttl_s"] as? Long)
            },
        )
    }

    fun cancel(id: Long) {
        api.call("POST", "/v1/devices/$dev/requests/$id/cancel", emptyMap<String, Any?>())
    }
}

/** O que a tela mostra depois do "Limpar" local (o log do guardião não é tocado). */
object Cleared {
    fun blocked(items: List<BlockedItem>, clearedAt: Long): List<BlockedItem> = items.filter { it.last > clearedAt }

    /** Pedidos abertos sempre aparecem; os respondidos somem até o id que foi limpado. */
    fun requests(all: List<UnblockRequest>, clearedId: Long): List<UnblockRequest> = all.filter { it.status == "open" || it.id > clearedId }

    fun newestSeen(items: List<BlockedItem>, current: Long): Long = maxOf(current, items.maxOfOrNull { it.last } ?: 0L)

    fun newestAnswered(all: List<UnblockRequest>, current: Long): Long = maxOf(current, all.filter { it.status != "open" }.maxOfOrNull { it.id } ?: 0L)
}

object UnblockText {
    fun ago(nowS: Long, ts: Long): String {
        val s = (nowS - ts).coerceAtLeast(0)
        return when {
            s < 90 -> "há $s s"
            s < 5400 -> "há ${s / 60} min"
            else -> "há ${s / 3600} h"
        }
    }

    fun ttl(s: Long?): String = when {
        s == null -> "sempre"
        s % 86400 == 0L -> "${s / 86400} d"
        s % 3600 == 0L -> "${s / 3600} h"
        else -> "${(s / 60).coerceAtLeast(1)} min"
    }

    fun stateLabel(i: RequestItem): String = when (i.state) {
        "open" -> "aguardando"
        "allowed" -> if (i.ttlS == null) "liberado" else "liberado por ${ttl(i.ttlS)}"
        "denied" -> "negado"
        else -> "cancelado"
    }

    private fun names(items: List<RequestItem>): String =
        items.take(3).joinToString(", ") { it.domain } + if (items.size > 3) " e mais ${items.size - 3}" else ""

    /** Texto da notificação quando o guardião respondeu; null se não há nada a avisar. */
    fun answer(req: UnblockRequest): String? {
        val allowed = req.items.filter { it.state == "allowed" }
        val denied = req.items.filter { it.state == "denied" }
        val parts = listOfNotNull(
            if (allowed.isNotEmpty()) "Liberado: ${names(allowed)}" else null,
            if (denied.isNotEmpty()) "Negado: ${names(denied)}" else null,
        )
        return if (parts.isEmpty()) null else parts.joinToString(". ") + if (allowed.isNotEmpty()) ". Se não abrir, espere 1 ou 2 minutos." else ""
    }
}

/** Notificação local quando o guardião responde. */
object UnblockNotifier {
    private const val CHANNEL = "pedidos"
    private const val ID = 4207

    @SuppressLint("MissingPermission")
    fun show(app: Context, text: String) {
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Pedidos de liberação", NotificationManager.IMPORTANCE_DEFAULT))
        nm.notify(
            ID,
            Notification.Builder(app, CHANNEL)
                .setContentTitle("Resposta do guardião")
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setAutoCancel(true)
                .build(),
        )
    }
}
