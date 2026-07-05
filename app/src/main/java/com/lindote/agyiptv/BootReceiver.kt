package com.lindote.agyiptv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i("BootReceiver", "Evento de arranque recebido: ${intent.action}")
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || 
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            
            try {
                val launchIntent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(launchIntent)
                Log.i("BootReceiver", "Aplicação AgyIPTV iniciada automaticamente com sucesso!")
            } catch (e: Exception) {
                Log.e("BootReceiver", "Erro ao iniciar AgyIPTV automaticamente: ${e.message}", e)
            }
        }
    }
}
