StepWake — Step-Activated Alarm (Android prototype)

Overview
--------
This is a minimal Android Kotlin prototype demonstrating a step-activated alarm: when the alarm triggers, a foreground service starts and counts steps using the hardware step counter (Sensor.TYPE_STEP_COUNTER). The alarm stops only when the configured number of steps is recorded.

What's included
---------------
- MainActivity: simple UI to set a demo alarm (30s delay) and a debug "simulate" mode.
- AlarmReceiver: triggers the foreground service when the AlarmManager fires.
- StepForegroundService: foreground service that reads the step sensor or simulates steps for testing. Shows an ongoing notification with remaining steps.
- Gradle build files to open in Android Studio.

How to run
----------
1. Open this folder in Android Studio.
2. Let Android Studio sync Gradle and install required SDKs (compileSdk & targetSdk 34 used here).
3. Run on a physical device (the emulator won't provide a step counter sensor). For quick testing use the "Start debug alarm (simulate steps)" button in the app — this will start the service in simulate mode and increment steps automatically.

Permissions
-----------
- ACTIVITY_RECOGNITION: required to read step counts on modern Android devices (requested at runtime by MainActivity).
- FOREGROUND_SERVICE: the app runs a foreground service while the alarm is active.
- RECEIVE_BOOT_COMPLETED: if you implement rescheduling of alarms across reboots (not implemented in this prototype yet).

Notes and next steps
--------------------
- This prototype focuses on the core step-based dismissal flow. Production-ready app needs: better UI, multiple alarm scheduling, persistence (Room), anti-cheat measures, thorough testing across devices and OEM Doze behaviors, and a boot reschedule handler.
 - This prototype focuses on the core step-based dismissal flow. Production-ready app needs: better UI, multiple alarm scheduling, persistence (Room), anti-cheat measures, thorough testing across devices and OEM Doze behaviors, and a boot reschedule handler.
- To test on a real device, use the debug simulate mode first, then test with the actual hardware step sensor.

Audible alarm
--------------
The foreground service now plays the device default alarm sound (or notification sound fallback) in a looping MediaPlayer while the alarm is active, and vibrates using the Vibrator API. The sound and vibration stop when the step goal is reached and the service stops.

UI
--
The demo app now includes a simple alarm editor UI. From the main screen you can:

- Choose an alarm time with the TimePicker.
- Set the step goal (default 8) and tap "Set Alarm" to schedule the alarm.
- Cancel the scheduled alarm with "Cancel Alarm".
- Start a debug/simulate alarm immediately with "Start debug alarm (simulate steps)" for testing without a hardware step sensor.

The scheduled alarm is shown on the screen and persisted in SharedPreferences so it can be rescheduled after a reboot (the prototype supports one scheduled alarm).

If you want, I can:
- Implement persistence and a full alarm editor UI.
- Add boot reschedule handling and rescheduling on app update.
- Add anti-cheat options and a PIN-cancel safety path.

