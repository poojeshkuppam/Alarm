StepWake — Web Prototype

Overview
--------
This is a standalone web prototype of the "StepWake" concept. It demonstrates the same UX as the Android prototype: schedule an alarm and require the user to take a number of steps to stop it.

Features
--------
- Schedule an alarm for a time of day (single alarm, persisted in localStorage).
- Configurable step goal.
- When the alarm triggers, the app plays a continuous tone and uses vibration (when supported).
- Uses DeviceMotion (devicemotion event) to detect steps using a simple peak detector where available.
- Includes a simulate/debug button and a "+1 simulated step" button for testing on desktops or unsupported devices.

Limitations
-----------
- Browsers cannot run reliably in the background like native apps. If you lock the screen or switch away, the alarm may be suspended by the browser/OS.
- DeviceMotion permission and behavior differ across platforms and browsers.
- Vibration and continuous audio behavior vary by device and browser; some platforms require a user gesture before audio will play.

How to run
----------
1. Open `web/index.html` in a modern browser (Chrome, Edge, Safari on mobile). For best results, open the URL on your phone and add the page to home screen.
2. Set an alarm time and step goal, then tap "Set Alarm".
3. When the alarm triggers, walk with the device (phone) — the app will try to detect steps via DeviceMotion. If your device/browser doesn't expose motion sensors, use "Start debug alarm (simulate)" and the "+1 simulated step" button.

Developer notes
---------------
- Step detection uses a lightweight peak detector subtracting a low-pass gravity estimate. It's not as accurate as a platform step sensor. Use the simulate controls for deterministic tests.
- You can change the `stepThreshold` and `minStepInterval` values in `app.js` to tune sensitivity per device.

Next steps
----------
- Add progressive web app (PWA) manifest and service worker to enable home-screen install and limited background behavior.
- Improve step detection with more advanced signal processing or integration with platform APIs when available.
- Add UI polish, sounds selection, and user onboarding for permission requests.

