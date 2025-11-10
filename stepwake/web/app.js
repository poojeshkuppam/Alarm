// Simple web prototype for StepWake
// - Schedules alarm by time
// - Uses DeviceMotion step detection when available (basic peak detector)
// - Provides simulated steps for testing

const timeInput = document.getElementById('timeInput')
const stepGoalInput = document.getElementById('stepGoal')
const setBtn = document.getElementById('setBtn')
const cancelBtn = document.getElementById('cancelBtn')
const simulateBtn = document.getElementById('simulateBtn')
const scheduledDiv = document.getElementById('scheduled')
const alarmState = document.getElementById('alarmState')
const remainingDiv = document.getElementById('remaining')
const sensorStatus = document.getElementById('sensorStatus')
const stepMock = document.getElementById('stepMock')
const sensitivity = document.getElementById('sensitivity')
const sensitivityVal = document.getElementById('sensitivityVal')
const calibrateBtn = document.getElementById('calibrateBtn')
const calibStatus = document.getElementById('calibStatus')
const enableBtn = document.getElementById('enableBtn')
const enableStatus = document.getElementById('enableStatus')
const webcamBtn = document.getElementById('webcamBtn')
const webcamStatus = document.getElementById('webcamStatus')
const webcamVideo = document.getElementById('webcamVideo')

let audioUnlocked = false
let motionGranted = false

let scheduledTime = null
let scheduledGoal = null
let alarmTimeout = null
let alarmActive = false
let stepsSinceStart = 0
let audioCtx = null
let osc = null
let vibratePattern = [500, 200, 500]

// alarm audio element (user-selected file or URL)
let alarmAudio = null
let alarmBlobUrl = null

// DeviceMotion-based step detection (very basic)
let gravity = 9.81
let lastStepTime = 0
let stepThreshold = 1.2 // m/s^2
let minStepInterval = 300 // ms
let dmListener = null
let baseThreshold = 1.2
let dynamicThreshold = baseThreshold
let sensitivityFactor = 1.0
let calibStats = {mean:0, std:0, samples:0}
let buffer = []
const BUF_SIZE = 15
// Webcam pose detector state
let useWebcam = false
let webcamStream = null
let poseDetector = null
let poseBuffer = []
const POSE_BUF = 20

function saveScheduled(timeMs, goal) {
  localStorage.setItem('sw_scheduled_time', String(timeMs))
  localStorage.setItem('sw_step_goal', String(goal))
}
function clearScheduled() {
  localStorage.removeItem('sw_scheduled_time')
  localStorage.removeItem('sw_step_goal')
}
function loadScheduled() {
  const t = parseInt(localStorage.getItem('sw_scheduled_time')||'0',10)
  const g = parseInt(localStorage.getItem('sw_step_goal')||'-1',10)
  if (t>0) {
    scheduledTime = t
    scheduledGoal = g
    scheduleTimeoutFor(t)
    updateScheduledUI()
  }
}

function updateScheduledUI(){
  if (!scheduledTime) {
    scheduledDiv.textContent = 'No alarm scheduled'
    return
  }
  const dt = new Date(scheduledTime)
  scheduledDiv.textContent = `Scheduled: ${dt.toLocaleString()} — ${scheduledGoal} steps`
}

function scheduleTimeoutFor(triggerAt) {
  if (alarmTimeout) {
    clearTimeout(alarmTimeout)
  }
  const delay = Math.max(0, triggerAt - Date.now())
  alarmTimeout = setTimeout(() => startAlarm(false), delay)
}

function computeTriggerFromTimeInput() {
  const timeVal = timeInput.value
  if (!timeVal) return null
  const [hh, mm] = timeVal.split(':').map(x=>parseInt(x,10))
  const now = new Date()
  const target = new Date()
  target.setHours(hh, mm, 0, 0)
  if (target.getTime() <= now.getTime()) target.setDate(target.getDate()+1)
  return target.getTime()
}

setBtn.addEventListener('click', ()=>{
  const triggerAt = computeTriggerFromTimeInput()
  if (!triggerAt) { alert('Please pick a time'); return }
  const goal = parseInt(stepGoalInput.value||'8',10)
  scheduledTime = triggerAt
  scheduledGoal = goal
  saveScheduled(triggerAt, goal)
  scheduleTimeoutFor(triggerAt)
  updateScheduledUI()
  alarmState.textContent = 'Alarm scheduled'
})

cancelBtn.addEventListener('click', ()=>{
  if (alarmTimeout) clearTimeout(alarmTimeout)
  scheduledTime = null
  scheduledGoal = null
  clearScheduled()
  updateScheduledUI()
  alarmState.textContent = 'Idle'
})

simulateBtn.addEventListener('click', ()=>{
  // start alarm in simulate mode immediately
  startAlarm(true)
})

stepMock.addEventListener('click', ()=>{
  if (!alarmActive) return
  addStep()
})

function startAlarm(simulate){
  alarmActive = true
  stepsSinceStart = 0
  alarmState.textContent = 'ALARM — Walk to stop'
  updateRemaining()
  startSound()
  startVibrate()
  if (!simulate) {
    // Prefer phone DeviceMotion; if not available and webcam requested, use webcam
    if (window.DeviceMotionEvent && !useWebcam) {
      sensorStatus.textContent = 'Sensor: waiting for movement...'
      startDeviceMotion()
    } else if (useWebcam) {
      sensorStatus.textContent = 'Webcam: waiting for movement...'
      startWebcam()
    } else {
      sensorStatus.textContent = 'Sensor: not supported — use simulate or enable webcam'
    }
  } else {
    sensorStatus.textContent = 'Simulate mode: use +1 simulated step'
  }
}

function stopAlarm(){
  alarmActive = false
  alarmState.textContent = 'Idle'
  updateRemaining()
  stopSound()
  stopVibrate()
  stopDeviceMotion()
  // clear persisted scheduled alarm if any
  scheduledTime = null; scheduledGoal = null; clearScheduled(); updateScheduledUI()
}

function updateRemaining(){
  if (!alarmActive) { remainingDiv.textContent = '—'; return }
  const remaining = Math.max(0, scheduledGoal - stepsSinceStart)
  remainingDiv.textContent = String(remaining)
}

function addStep(){
  stepsSinceStart++
  updateRemaining()
  if (stepsSinceStart >= scheduledGoal) stopAlarm()
}

// Sound: prefer user-selected audio (file or URL), fallback to WebAudio oscillator
function startSound(){
  try {
    const fileInput = document.getElementById('alarmFile')
    const urlInput = document.getElementById('alarmUrl')
    const file = fileInput ? fileInput.files[0] : null
    const url = urlInput ? urlInput.value.trim() : ''

    if (file) {
      // create or reuse audio element with blob URL
      if (!alarmAudio) alarmAudio = new Audio()
      if (alarmBlobUrl) URL.revokeObjectURL(alarmBlobUrl)
      alarmBlobUrl = URL.createObjectURL(file)
      alarmAudio.src = alarmBlobUrl
      alarmAudio.loop = true
      alarmAudio.play().catch(()=>{})
      return
    }

    if (url) {
      if (!alarmAudio) alarmAudio = new Audio()
      alarmAudio.src = url
      alarmAudio.loop = true
      alarmAudio.play().catch(()=>{})
      return
    }

    // fallback: oscillator tone
    audioCtx = new (window.AudioContext || window.webkitAudioContext)()
    osc = audioCtx.createOscillator()
    const gain = audioCtx.createGain()
    osc.type = 'sine'
    osc.frequency.value = 440 // A4
    gain.gain.value = 0.03
    osc.connect(gain)
    gain.connect(audioCtx.destination)
    osc.start()
  } catch(e) {
    console.warn('Audio start failed', e)
  }
}
function stopSound(){
  try {
    if (alarmAudio) {
      try { alarmAudio.pause() } catch(e){}
      if (alarmBlobUrl) { URL.revokeObjectURL(alarmBlobUrl); alarmBlobUrl = null }
      alarmAudio.src = ''
      alarmAudio = null
    }
  } catch(e){}
  try {
    if (osc) osc.stop()
    if (audioCtx) audioCtx.close()
  } catch(e){}
  osc = null; audioCtx = null
}

function startVibrate(){
  try {
    if (navigator.vibrate) navigator.vibrate(vibratePattern)
  } catch(e){}
}
function stopVibrate(){
  try { if (navigator.vibrate) navigator.vibrate(0) } catch(e){}
}

// Basic DeviceMotion peak detection
function startDeviceMotion(){
  if (dmListener) return
  gravity = {x: 0, y: 0, z: 0}
  
  dmListener = function(e){
    const a = e.accelerationIncludingGravity
    if (!a) return
    const ax = a.x||0, ay = a.y||0, az = a.z||0

    // Update gravity with low-pass filter (separate components)
    gravity.x = GRAVITY_ALPHA * gravity.x + (1 - GRAVITY_ALPHA) * ax
    gravity.y = GRAVITY_ALPHA * gravity.y + (1 - GRAVITY_ALPHA) * ay
    gravity.z = GRAVITY_ALPHA * gravity.z + (1 - GRAVITY_ALPHA) * az
    
    // Get linear acceleration (high-pass: remove gravity)
    const linearX = ax - gravity.x
    const linearY = ay - gravity.y
    const linearZ = az - gravity.z
    
    // Use vertical component (Y) with some influence from X/Z
    const linear = Math.abs(linearY) + 0.3 * Math.sqrt(linearX*linearX + linearZ*linearZ)

    // maintain circular buffer for local peak detection
    buffer.push(linear)
    if (buffer.length > BUF_SIZE) buffer.shift()

    // dynamic threshold: use running mean/std of buffer
    const mean = buffer.reduce((s,v)=>s+v,0)/buffer.length
    const variance = buffer.reduce((s,v)=>s+(v-mean)*(v-mean),0)/buffer.length
    const std = Math.sqrt(variance)
    dynamicThreshold = Math.max(0.12, (mean + 0.8*std) / sensitivityFactor)

    // peak detection: previous sample is local max if it's greater than neighbors
    const len = buffer.length
    if (len >= 3) {
      const prev = buffer[len-2]
      const prevPrev = buffer[len-3]
      const curr = buffer[len-1]
      const now = Date.now()
      if (prev > prevPrev && prev > curr && prev > dynamicThreshold && (now - lastStepTime) > minStepInterval) {
        lastStepTime = now
        if (alarmActive) addStep()
      }
    }
    // update sensor status live
    sensorStatus.textContent = `Sensor: filtered ${linear.toFixed(2)} threshold ${dynamicThreshold.toFixed(2)}`
  }
  window.addEventListener('devicemotion', dmListener)
}
function stopDeviceMotion(){
  if (!dmListener) return
  window.removeEventListener('devicemotion', dmListener)
  dmListener = null
}

// Webcam-based step detection using pose-detection MoveNet
async function startWebcam(){
  if (poseDetector) return
  try {
    // Prefer to pick a known video input device when available - facingMode can fail on some desktops
    let constraints = { video: { facingMode: 'user' }, audio: false }

    if (navigator.mediaDevices && navigator.mediaDevices.enumerateDevices) {
      try {
        const devices = await navigator.mediaDevices.enumerateDevices()
        const cams = devices.filter(d => d.kind === 'videoinput')
        if (cams.length === 0) {
          throw new Error('No video input devices found')
        }
        // try to find a front-facing or integrated camera by label if available
        let chosen = null
        for (const c of cams) {
          const label = (c.label||'').toLowerCase()
          if (label.includes('front') || label.includes('integrated') || label.includes('face')) { chosen = c; break }
        }
        if (!chosen) chosen = cams[0]
        if (chosen && chosen.deviceId) {
          constraints = { video: { deviceId: { exact: chosen.deviceId } }, audio: false }
        }
      } catch (enumErr) {
        // enumeration may fail on insecure origins or without permission; fall back to generic constraints
        console.warn('enumerateDevices failed', enumErr)
        constraints = { video: true, audio: false }
      }
    }

    webcamStream = await navigator.mediaDevices.getUserMedia(constraints)
    if (!webcamStream || webcamStream.getVideoTracks().length === 0) {
      // try a simple fallback
      webcamStream = await navigator.mediaDevices.getUserMedia({ video: true, audio: false })
    }

    webcamVideo.srcObject = webcamStream
    webcamVideo.style.display = 'block'
    try { await webcamVideo.play() } catch(playErr) { console.warn('video play failed', playErr) }

    // create detector (MoveNet) if available
    if (window.poseDetection) {
      try {
        poseDetector = await poseDetection.createDetector(poseDetection.SupportedModels.MoveNet)
      } catch(detErr) {
        console.warn('pose detector creation failed', detErr)
        poseDetector = null
      }
    }

    // start loop
    requestAnimationFrame(poseLoop)
    webcamStatus.textContent = 'Webcam: running'
  } catch (e) {
    console.warn('Webcam start failed', e)
    webcamStatus.textContent = 'Webcam: failed'
    // provide more helpful status for the user
    if (e && e.name === 'NotAllowedError') webcamStatus.textContent = 'Webcam: permission denied'
    else if (e && e.message && e.message.includes('No video input')) webcamStatus.textContent = 'Webcam: no camera found'
  }
}

async function stopWebcam(){
  if (webcamStream) {
    webcamStream.getTracks().forEach(t=>t.stop())
    webcamStream = null
  }
  if (webcamVideo) {
    webcamVideo.pause()
    webcamVideo.srcObject = null
    webcamVideo.style.display = 'none'
  }
  poseDetector = null
  poseBuffer = []
  webcamStatus.textContent = 'Webcam: off'
}

async function poseLoop(){
  if (!poseDetector || !webcamVideo || webcamVideo.readyState < 2) {
    if (alarmActive && useWebcam) requestAnimationFrame(poseLoop)
    return
  }
  try {
    const poses = await poseDetector.estimatePoses(webcamVideo)
    if (poses && poses.length>0) {
      const k = poses[0].keypoints
      // find left_hip/right_hip or use hips by index
      const left = k.find(p=>p.name==='left_hip' || p.name==='leftHip')
      const right = k.find(p=>p.name==='right_hip' || p.name==='rightHip')
      let hipY = null
      if (left && right && left.score>0.3 && right.score>0.3) {
        hipY = (left.y + right.y)/2
      } else {
        // fallback to nose or center
        const nose = k.find(p=>p.name==='nose')
        if (nose) hipY = nose.y
      }
      if (hipY!=null) {
        // normalize by video height and invert (smaller y is top)
        const norm = (webcamVideo.videoHeight - hipY) / webcamVideo.videoHeight
        poseBuffer.push(norm)
        if (poseBuffer.length>POSE_BUF) poseBuffer.shift()

        // detect peaks in poseBuffer as steps
        if (poseBuffer.length>=5) {
          const len = poseBuffer.length
          const prev = poseBuffer[len-2]
          const prevPrev = poseBuffer[len-3]
          const curr = poseBuffer[len-1]
          // dynamic threshold based on recent variance
          const mean = poseBuffer.reduce((s,v)=>s+v,0)/poseBuffer.length
          const variance = poseBuffer.reduce((s,v)=>s+(v-mean)*(v-mean),0)/poseBuffer.length
          const std = Math.sqrt(variance)
          const dyn = Math.max(0.005, mean + 0.6*std)
          const now = Date.now()
          if (prev > prevPrev && prev > curr && prev > dyn && (now - lastStepTime) > minStepInterval) {
            lastStepTime = now
            if (alarmActive) addStep()
          }
          // update sensor status for debug
          sensorStatus.textContent = `Webcam: ${curr.toFixed(3)} thr ${dyn.toFixed(3)} `
        }
      }
    }
  } catch(e) {
    console.warn('poseLoop error', e)
  }
  if (alarmActive && useWebcam) requestAnimationFrame(poseLoop)
}

webcamBtn.addEventListener('click', async ()=>{
  useWebcam = !useWebcam
  if (useWebcam) {
    await startWebcam()
    webcamBtn.textContent = 'Disable webcam'
  } else {
    await stopWebcam()
    webcamBtn.textContent = 'Use webcam for steps'
  }
})

// load persisted data
loadScheduled()

// Best-effort ask for permission on iOS 13+
function requestMotionPermissionIfNeeded(){
  if (typeof DeviceMotionEvent !== 'undefined' && typeof DeviceMotionEvent.requestPermission === 'function'){
    DeviceMotionEvent.requestPermission().then(result=>{
      sensorStatus.textContent = result === 'granted' ? 'Sensor permission granted' : 'Sensor permission denied'
      if (result === 'granted') motionGranted = true
    }).catch(err=>{
      sensorStatus.textContent = 'Sensor permission error'
    })
  }
}

// request on load for mobile
window.addEventListener('load', ()=>{
  requestMotionPermissionIfNeeded()
  sensitivityVal.textContent = sensitivity.value
})

// Enable audio and motion (user gesture to unlock audio autoplay and request motion permission)
enableBtn.addEventListener('click', async ()=>{
  enableStatus.textContent = 'Enabling...'
  // request motion permission where necessary
  if (typeof DeviceMotionEvent !== 'undefined' && typeof DeviceMotionEvent.requestPermission === 'function'){
    try {
      const res = await DeviceMotionEvent.requestPermission()
      motionGranted = (res === 'granted')
      enableStatus.textContent = motionGranted ? 'Motion granted' : 'Motion denied'
    } catch(e) {
      enableStatus.textContent = 'Motion permission error'
    }
  }

  // create and resume an AudioContext to unlock audio on browsers
  try {
    const ctx = new (window.AudioContext || window.webkitAudioContext)()
    await ctx.resume()
    await ctx.suspend()
    // keep no persistent audioCtx to avoid resource hold; mark unlocked
    audioUnlocked = true
    enableStatus.textContent = (motionGranted ? 'Enabled' : 'Audio enabled')
    enableBtn.disabled = true
  } catch(e) {
    console.warn('Audio unlock failed', e)
    enableStatus.textContent = 'Audio unlock failed'
  }
})

// sensitivity changes
sensitivity.addEventListener('input', ()=>{
  sensitivityFactor = parseFloat(sensitivity.value)
  sensitivityVal.textContent = sensitivity.value
})

// calibration: sample N seconds to compute baseline mean/std
calibrateBtn.addEventListener('click', ()=>{
  calibStatus.textContent = 'Calibrating…'
  buffer = []
  let samples = []
  const onSample = (e)=>{
    const a = e.accelerationIncludingGravity
    if (!a) return
    const mag = Math.sqrt((a.x||0)*(a.x||0) + (a.y||0)*(a.y||0) + (a.z||0)*(a.z||0))
    // update gravity quickly for calibration
    gravity = 0.9*gravity + 0.1*mag
    const linear = Math.abs(mag - gravity)
    samples.push(linear)
  }
  window.addEventListener('devicemotion', onSample)
  setTimeout(()=>{
    window.removeEventListener('devicemotion', onSample)
    if (samples.length===0) {
      calibStatus.textContent = 'Calibration failed (no sensor)'
      return
    }
    const mean = samples.reduce((s,v)=>s+v,0)/samples.length
    const variance = samples.reduce((s,v)=>s+(v-mean)*(v-mean),0)/samples.length
    const std = Math.sqrt(variance)
    baseThreshold = Math.max(0.12, mean + 0.8*std)
    dynamicThreshold = baseThreshold / sensitivityFactor
    calibStatus.textContent = `Calibrated (mean ${mean.toFixed(2)} std ${std.toFixed(2)})`
  }, 3000)
})
 
