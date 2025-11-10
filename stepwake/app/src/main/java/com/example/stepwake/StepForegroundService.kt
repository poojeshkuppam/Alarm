package com.example.stepwake

import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.media.MediaPlayer
import android.net.Uri
import android.media.RingtoneManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

class StepForegroundService : Service(), SensorEventListener {

    private val TAG = "StepForegroundService"
    private lateinit var sensorManager: SensorManager
    private var stepSensor: Sensor? = null
    private var accelSensor: Sensor? = null
    private var gyroSensor: Sensor? = null
    private var linearAccelSensor: Sensor? = null

    private var baseline: Float = -1f
    private var stepGoal: Int = 8
    private var simulate: Boolean = false

    private var simulatedSteps = 0
    private var simulateHandler: Handler? = null
    private var currentStepsSinceStart: Int = 0
    // for accel/gyro based step detection
    private var lastAccel = FloatArray(3) { 0f }
    private var highPass = FloatArray(3) { 0f }
    private var lastStepTimestamp: Long = 0
    private val STEP_DETECTION_THRESHOLD = 1.2f // m/s^2, tweak if needed
    private val MIN_STEP_INTERVAL_MS = 300L
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        // try to get linear accel first (already gravity removed). If not available, use accel+gyro fusion
        linearAccelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stepGoal = intent?.getIntExtra("stepGoal", 8) ?: 8
        simulate = intent?.getBooleanExtra("simulate", false) ?: false

        startForegroundServiceWithNotification("", stepGoal)

        // start alarm sound + vibration
        startAlarmSoundAndVibrate()

        if (simulate) startSimulation() else registerStepSensor()

        return START_NOT_STICKY
    }

    private fun startAlarmSoundAndVibrate() {
        try {
            val uri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            mediaPlayer = MediaPlayer.create(this, uri)
            mediaPlayer?.isLooping = true
            mediaPlayer?.start()
        } catch (e: Exception) {
            // fallback ignored
        }

        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createWaveform(longArrayOf(0, 500, 500), 0)
                vibrator?.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(longArrayOf(0, 500, 500), 0)
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun startSimulation() {
        val thread = HandlerThread("simulate-thread")
        thread.start()
        simulateHandler = Handler(thread.looper)
        simulateHandler?.postDelayed(object : Runnable {
            override fun run() {
                simulatedSteps++
                onSimulatedStep(simulatedSteps.toFloat())
                if (!isGoalReached()) {
                    simulateHandler?.postDelayed(this, 1000L)
                }
            }
        }, 1000L)
    }

    private fun onSimulatedStep(value: Float) {
        if (baseline < 0f) baseline = 0f
        val stepsSinceStart = value - baseline
        currentStepsSinceStart = stepsSinceStart.toInt()
        updateNotification(currentStepsSinceStart)
        if (isGoalReached()) {
            stopSelf()
        }
    }

    private fun registerStepSensor() {
        stepSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            Log.i(TAG, "Registered TYPE_STEP_COUNTER")
            return
        }

        // fallback: try linear acceleration (preferred) or accelerometer + gyro
        linearAccelSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            Log.i(TAG, "Registered TYPE_LINEAR_ACCELERATION fallback")
            return
        }

        if (accelSensor != null) {
            sensorManager.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_GAME)
            Log.i(TAG, "Registered TYPE_ACCELEROMETER fallback")
        } else {
            Log.w(TAG, "Accelerometer not available")
        }
        // gyro is optional but can help if you later fuse data
        gyroSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            Log.i(TAG, "Registered TYPE_GYROSCOPE (optional)")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        simulateHandler?.removeCallbacksAndMessages(null)
        sensorManager.unregisterListener(this)
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (e: Exception) {}
        try {
            vibrator?.cancel()
        } catch (e: Exception) {}
        // clear persisted scheduled alarm so it won't be rescheduled after stop
        try {
            val prefs = getSharedPreferences("stepwake_prefs", Context.MODE_PRIVATE)
            prefs.edit().remove("scheduled_time").remove("step_goal").apply()
        } catch (e: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        when (event.sensor.type) {
            Sensor.TYPE_STEP_COUNTER -> {
                val value = event.values[0]
                if (baseline < 0f) baseline = value
                val stepsSinceStart = value - baseline
                currentStepsSinceStart = stepsSinceStart.toInt()
                updateNotification(currentStepsSinceStart)
                if (isGoalReached()) stopSelf()
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                handleAccel(event.values[0], event.values[1], event.values[2], event.timestamp)
            }
            Sensor.TYPE_ACCELEROMETER -> {
                // apply a simple high-pass filter to remove gravity
                handleAccel(event.values[0], event.values[1], event.values[2], event.timestamp)
            }
            Sensor.TYPE_GYROSCOPE -> {
                // currently not fused; reserved for future improvement
                // could be used to improve high-pass filtering or detect device orientation changes
            }
        }
    }

    private fun handleAccel(x: Float, y: Float, z: Float, timestamp: Long) {
        // simple high-pass filter: remove slow-changing gravity component
        val alpha = 0.8f
        val accel = floatArrayOf(x, y, z)
        for (i in 0..2) {
            highPass[i] = alpha * (highPass[i] + accel[i] - lastAccel[i])
            lastAccel[i] = accel[i]
        }
        val mag = Math.sqrt((highPass[0] * highPass[0] + highPass[1] * highPass[1] + highPass[2] * highPass[2]).toDouble()).toFloat()

        val nowMs = System.currentTimeMillis()
        if (mag > STEP_DETECTION_THRESHOLD && (nowMs - lastStepTimestamp) > MIN_STEP_INTERVAL_MS) {
            lastStepTimestamp = nowMs
            currentStepsSinceStart++
            updateNotification(currentStepsSinceStart)
            Log.d(TAG, "Accel-detected step. mag=$mag, total=$currentStepsSinceStart")
            if (isGoalReached()) stopSelf()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun startForegroundServiceWithNotification(initialText: String, goal: Int) {
        val channelId = createNotificationChannel()
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, getPendingIntentFlags())

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("StepWake — Alarm active")
            .setContentText("Walk $goal steps to stop the alarm")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(101, notification)
    }

    private fun updateNotification(stepsDone: Int) {
        val channelId = "stepwake_channel"
        val remaining = stepGoal - stepsDone
        val text = if (remaining > 0) "$remaining steps remaining" else "Goal reached"
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("StepWake — Alarm active")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true)
            .build()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(101, notification)
    }

    private fun isGoalReached(): Boolean {
        if (baseline < 0f && !simulate) return false
        return currentStepsSinceStart >= stepGoal
    }

    private fun createNotificationChannel(): String {
        val channelId = "stepwake_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "StepWake alarms", NotificationManager.IMPORTANCE_HIGH)
            channel.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
        return channelId
    }

    private fun getPendingIntentFlags(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
    }
}
