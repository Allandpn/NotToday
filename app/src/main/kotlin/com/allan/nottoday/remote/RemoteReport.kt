package com.allan.nottoday.remote

import android.content.Context
import com.allan.nottoday.Catalog
import com.allan.nottoday.Clocks
import com.allan.nottoday.Config
import com.allan.nottoday.DevWindow
import com.allan.nottoday.Health
import com.allan.nottoday.Maintenance
import com.allan.nottoday.PackageGuard
import com.allan.nottoday.Pending
import com.allan.nottoday.PendingStore
import com.allan.nottoday.PinStore
import com.allan.nottoday.PolicyManager

/** Relatório que o site do guardião mostra (campos em protocol/SPEC.md). */
object RemoteReport {
    private const val MAX_APPS = 50

    fun build(context: Context, consumer: Consumer, executed: List<Map<String, Any?>>): Map<String, Any?> {
        val policy = PolicyManager(context)
        val config = Config(context)
        val pendings = PendingStore(context)
        val clock = Clocks.now(context)
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (_: Exception) {
            "?"
        }
        val pendingApps = if (policy.isDeviceOwner() || RemoteStore(context).isPaired) {
            PackageGuard.pending(context).take(MAX_APPS).map {
                mapOf("pkg" to it, "label" to PackageGuard.label(context, it), "risks" to PackageGuard.risks(context, it))
            }
        } else {
            emptyList()
        }
        val pending = Pending.Kind.entries.mapNotNull { kind ->
            val p = pendings.get(kind) ?: return@mapNotNull null
            when (val st = p.status(clock)) {
                is Pending.Status.Waiting -> mapOf("kind" to kind.name, "state" to "waiting", "remainingMs" to st.remainingMs, "summary" to summary(kind, p))
                is Pending.Status.Ready -> mapOf("kind" to kind.name, "state" to "ready", "remainingMs" to 0L, "summary" to summary(kind, p))
                Pending.Status.Expired -> null
            }
        }
        val dev = DevWindow(context)
        return linkedMapOf(
            "kind" to "device",
            "ts" to System.currentTimeMillis() / 1000,
            "version" to version,
            "owner" to policy.isDeviceOwner(),
            "activated" to RemoteStore(context).activated,
            "filterActive" to policy.filterActive(),
            "filterReason" to Health.lastDetail,
            "tailscale" to (Catalog.TAILSCALE_PKG in PackageGuard.installedSet(context)),
            "pinSet" to PinStore(context).isSet,
            "pendingApps" to pendingApps,
            "pending" to pending,
            "windows" to mapOf("maintenanceMs" to Maintenance(context).remainingMs(), "devMs" to dev.remainingMs(), "devUsed" to dev.usedInLast24h()),
            "snapshot" to config.snapshot(),
            "trust" to consumer.trust.guardians.mapValues { (_, g) -> g.creds.keys.sorted() },
            "ctrs" to consumer.lastCtr,
            "executed" to executed.takeLast(50),
        )
    }

    private fun summary(kind: Pending.Kind, p: Pending): String = when (kind) {
        Pending.Kind.SETTINGS -> "${p.payload.size} alteração(ões): " + p.payload.keys.take(6).joinToString(", ")
        Pending.Kind.RELEASE -> "remover o Device Owner"
        Pending.Kind.MAINTENANCE -> "janela de manutenção"
        Pending.Kind.GUARDIAN -> "remover guardião ${p.payload["id"] ?: "?"}"
    }
}
