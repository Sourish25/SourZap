package com.sourzap.app.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sourzap.app.SourZapApp

/**
 * BroadcastReceiver triggered by the "Cancel" action button in the update download notification.
 */
class UpdateCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == UpdateManager.ACTION_CANCEL_UPDATE) {
            try {
                SourZapApp.instance.updateManager.cancelDownload()
            } catch (_: Throwable) {
                // Ignore if app instance is not initialized
            }
        }
    }
}
