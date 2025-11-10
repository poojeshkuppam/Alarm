package com.example.stepwake

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.TimePicker
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.text.DateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var setAlarmButton: Button
    private lateinit var cancelAlarmButton: Button
    private lateinit var setDebugAlarmButton: Button
    private lateinit var stepGoalInput: EditText
    private lateinit var statusText: TextView
    private lateinit var timePicker: TimePicker
    private lateinit var scheduledText: TextView

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        // no-op for now
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setAlarmButton = findViewById(R.id.btnSetAlarm)
        cancelAlarmButton = findViewById(R.id.btnCancelAlarm)
        setDebugAlarmButton = findViewById(R.id.btnSetDebugAlarm)
        stepGoalInput = findViewById(R.id.etStepGoal)
        statusText = findViewById(R.id.tvStatus)
        timePicker = findViewById(R.id.timePicker)
        scheduledText = findViewById(R.id.tvScheduled)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
            }
        }

        setAlarmButton.setOnClickListener {
            val stepGoal = stepGoalInput.text.toString().toIntOrNull() ?: 8
            val triggerAt = computeTriggerTime()
            scheduleAlarmAt(triggerAt, stepGoal)
            val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            statusText.text = "Alarm scheduled at ${fmt.format(Date(triggerAt))} (goal $stepGoal steps)"
            displayScheduledAlarm()
        }

        setDebugAlarmButton.setOnClickListener {
            val stepGoal = stepGoalInput.text.toString().toIntOrNull() ?: 8
            // Directly start the foreground service in simulate mode for quick testing
            val intent = Intent(this, StepForegroundService::class.java)
            intent.putExtra("stepGoal", stepGoal)
            intent.putExtra("simulate", true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            statusText.text = "Debug alarm started (simulate mode) with goal $stepGoal"
        }

        cancelAlarmButton.setOnClickListener {
            cancelAlarm()
        }

        displayScheduledAlarm()
    }

    private fun computeTriggerTime(): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance()
        val hour = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) timePicker.hour else timePicker.currentHour
        val minute = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) timePicker.minute else timePicker.currentMinute
        target.set(Calendar.HOUR_OF_DAY, hour)
        target.set(Calendar.MINUTE, minute)
        target.set(Calendar.SECOND, 0)
        target.set(Calendar.MILLISECOND, 0)
        if (target.timeInMillis <= now.timeInMillis) {
            target.add(Calendar.DAY_OF_MONTH, 1)
        }
        return target.timeInMillis
    }

    private fun displayScheduledAlarm() {
        val prefs = getSharedPreferences("stepwake_prefs", Context.MODE_PRIVATE)
        val scheduled = prefs.getLong("scheduled_time", 0L)
        val goal = prefs.getInt("step_goal", -1)
        if (scheduled <= 0L) {
            scheduledText.text = "No alarm scheduled"
        } else {
            val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            scheduledText.text = "Scheduled: ${fmt.format(Date(scheduled))} — $goal steps"
        }
    }

    private fun cancelAlarm() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, AlarmReceiver::class.java)
        val pending = PendingIntent.getBroadcast(
            this,
            1001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or getImmutableFlag()
        )
        alarmManager.cancel(pending)
        val prefs = getSharedPreferences("stepwake_prefs", Context.MODE_PRIVATE)
        prefs.edit().remove("scheduled_time").remove("step_goal").apply()
        statusText.text = "Alarm canceled"
        displayScheduledAlarm()
    }

    private fun scheduleAlarm(delayMs: Long, stepGoal: Int) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, AlarmReceiver::class.java)
        intent.putExtra("stepGoal", stepGoal)

        val pending = PendingIntent.getBroadcast(
            this,
            1001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or getImmutableFlag()
        )

        val triggerAt = System.currentTimeMillis() + delayMs
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }
        // persist scheduled alarm so we can reschedule after reboot
        val prefs = getSharedPreferences("stepwake_prefs", Context.MODE_PRIVATE)
        prefs.edit().putLong("scheduled_time", triggerAt).putInt("step_goal", stepGoal).apply()
    }

    private fun scheduleAlarmAt(triggerAt: Long, stepGoal: Int) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, AlarmReceiver::class.java)
        intent.putExtra("stepGoal", stepGoal)

        val pending = PendingIntent.getBroadcast(
            this,
            1001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or getImmutableFlag()
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }

        val prefs = getSharedPreferences("stepwake_prefs", Context.MODE_PRIVATE)
        prefs.edit().putLong("scheduled_time", triggerAt).putInt("step_goal", stepGoal).apply()
    }

    private fun getImmutableFlag(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
    }
}
