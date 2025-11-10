package com.example.stepwake

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val stepGoal = intent.getIntExtra("stepGoal", 8)
        val serviceIntent = Intent(context, StepForegroundService::class.java)
        serviceIntent.putExtra("stepGoal", stepGoal)
        serviceIntent.putExtra("simulate", false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
