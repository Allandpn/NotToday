package com.allan.nottoday

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

class AdminReceiver : DeviceAdminReceiver() {
    /** Chamado logo após `dpm set-device-owner`: já aplica o estado padrão. */
    override fun onEnabled(context: Context, intent: Intent) {
        // Pareado com Guardian remoto: o PIN vem do site. Abrir a janela deixaria o usuário criar um PIN próprio.
        if (!com.allan.nottoday.remote.RemoteStore(context).isPaired) PinStore(context).openFirstPinWindow()
        runAsync { PolicyManager(context).apply() }
    }
}
