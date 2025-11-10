package com.example.stepwake

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = context.getSharedPreferences("stepwake_prefs", Context.MODE_PRIVATE)
        val scheduledTime = prefs.getLong("scheduled_time", 0L)
        val stepGoal = prefs.getInt("step_goal", 8)
        if (scheduledTime <= System.currentTimeMillis()) {
            // nothing to reschedule
            return
        }

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val alarmIntent = Intent(context, AlarmReceiver::class.java)
        alarmIntent.putExtra("stepGoal", stepGoal)
        val pending = PendingIntent.getBroadcast(
            context,
            1001,
            alarmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or getImmutableFlag()
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, scheduledTime, pending)
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, scheduledTime, pending)
        }
    }

    private fun getImmutableFlag(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
    }
}
