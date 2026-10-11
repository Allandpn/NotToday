package com.allan.nottoday

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

class AdminReceiver : DeviceAdminReceiver() {
    /** Chamado logo após `dpm set-device-owner`: já aplica o estado padrão. */
    override fun onEnabled(context: Context, intent: Intent) {
        PinStore(context).openFirstPinWindow()
        runAsync { PolicyManager(context).apply() }
    }
}
