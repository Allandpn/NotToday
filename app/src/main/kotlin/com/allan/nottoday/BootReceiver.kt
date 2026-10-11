package com.allan.nottoday

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Reaplica o estado desejado após reiniciar ou atualizar o app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        runAsync(onDone = pending::finish) { PolicyManager(context).apply() }
    }
}
