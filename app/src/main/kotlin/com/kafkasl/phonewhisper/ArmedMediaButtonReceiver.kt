package com.kafkasl.phonewhisper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class ArmedMediaButtonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return

        ContextCompat.startForegroundService(
            context,
            Intent(context, ArmedHeadsetService::class.java)
                .setAction(Intent.ACTION_MEDIA_BUTTON)
                .putExtras(intent)
        )
    }
}
