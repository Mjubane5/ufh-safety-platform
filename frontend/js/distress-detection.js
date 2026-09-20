/**
 * Live audio and camera monitoring for an active emergency, wired up from
 * incident.js. Never part of sending the SOS or medical alert itself - it
 * only starts AFTER an incident already exists, automatically as soon as
 * the reporter's incident.html page loads. The browser's own
 * microphone/camera permission prompts are the real consent gate - nothing
 * here can capture anything the student did not approve there.
 *
 * Only ever produces DERIVED signals - a transcript line, a sound-event
 * label, a facial-expression label - passed to the onX callbacks below for
 * the caller to send via api.js's appendIncidentSignal(). Raw audio and
 * video never leave this module, are never recorded, and are never sent
 * anywhere.
 *
 * Three independent pieces, each optional and each failing quietly on its
 * own (a phone with no webcam, a browser with no Speech API, or no network
 * access to the model hosts below should not stop the other two):
 *
 *   1. Speech-to-text  - the browser's built-in Web Speech API. No extra
 *      library, no model download.
 *   2. Sound events     - Google's YAMNet, a pretrained 521-class sound
 *      classifier, run client-side with TensorFlow.js. Flags a short
 *      allowlist of distress-relevant classes (screaming, shouting, crying,
 *      and a few others - see SOUND_LABEL_ALLOWLIST).
 *   3. Facial expression - face-api.js (tiny face detector + an expression
 *      model), also run client-side, flags a high fear/sadness/anger score.
 *
 * Both (2) and (3) load real pretrained models from third-party hosts over
 * the network the first time they're used (tens of MB combined) - see the
 * URL constants below for exactly what is loaded from where. Confidence
 * thresholds are first-pass estimates, not tuned against real recordings
 * (this was built and reviewed without microphone/camera access to verify
 * against), and should be adjusted once the team can test with real audio
 * and faces.
 */

const SOUND_LABEL_ALLOWLIST = new Set([
  'Shout',
  'Bellow',
  'Whoop',
  'Yell',
  'Children shouting',
  'Screaming',
  'Crying, sobbing',
  'Baby cry, infant cry',
  'Gunshot, gunfire',
  'Slap, smack',
]);
// YAMNet's output is 521 independent sigmoid scores (multi-label), not a
// softmax over one answer, so scores for a single clip sit much lower than a
// classifier trained on one label per clip - this threshold is a starting
// point only, see the file-level note above.
const SOUND_CONFIDENCE_THRESHOLD = 0.15;
const FACE_DISTRESS_THRESHOLD = 0.55;
const SOUND_WINDOW_MS = 1000;
const FACE_SAMPLE_MS = 2000;

const YAMNET_MODEL_URL = 'https://tfhub.dev/google/tfjs-model/yamnet/tfjs/1';
const YAMNET_CLASS_MAP_URL = 'https://raw.githubusercontent.com/tensorflow/models/master/research/audioset/yamnet/yamnet_class_map.csv';
const TFJS_SCRIPT_URL = 'https://cdn.jsdelivr.net/npm/@tensorflow/tfjs@4.22.0/dist/tf.min.js';
const FACEAPI_SCRIPT_URL = 'https://cdn.jsdelivr.net/npm/face-api.js@0.22.2/dist/face-api.min.js';
const FACEAPI_MODELS_URL = 'https://cdn.jsdelivr.net/gh/justadudewhohacks/face-api.js@master/weights';

let running = false;
let recognition = null;
let micStream = null;
let camStream = null;
let yamnetModel = null;
let yamnetClasses = null;
let stopHandlers = [];

function loadScriptOnce(src) {
  return new Promise((resolve, reject) => {
    if (document.querySelector(`script[src="${src}"]`)) {
      resolve();
      return;
    }
    const script = document.createElement('script');
    script.src = src;
    script.onload = () => resolve();
    script.onerror = () => reject(new Error(`Could not load ${src}`));
    document.head.appendChild(script);
  });
}

// ---------------------------------------------------------------------------
// 1. Speech-to-text
// ---------------------------------------------------------------------------

function startTranscription(onTranscript, onStatus) {
  const Impl = window.SpeechRecognition || window.webkitSpeechRecognition;
  if (!Impl) {
    onStatus('Speech transcription is not supported in this browser.');
    return null;
  }

  const recognizer = new Impl();
  recognizer.continuous = true;
  recognizer.interimResults = false;
  recognizer.lang = 'en-ZA';

  recognizer.onresult = (event) => {
    for (let i = event.resultIndex; i < event.results.length; i += 1) {
      const result = event.results[i];
      if (result.isFinal) {
        const text = result[0].transcript.trim();
        if (text) onTranscript(text);
      }
    }
  };
  recognizer.onerror = (event) => {
    // "no-speech" fires constantly during silence - not an error worth
    // surfacing. Anything else is worth telling the student about.
    if (event.error === 'no-speech' || event.error === 'aborted') return;
    onStatus(`Transcription stopped: ${event.error}`);
  };
  // The browser stops recognition on its own every so often even while
  // still listening - restart it seamlessly as long as we're still running.
  recognizer.onend = () => {
    if (running) {
      try {
        recognizer.start();
      } catch {
        // Already starting - ignore.
      }
    }
  };

  recognizer.start();
  return recognizer;
}

// ---------------------------------------------------------------------------
// 2. Sound-event detection (YAMNet via TensorFlow.js)
// ---------------------------------------------------------------------------

async function loadYamnet() {
  await loadScriptOnce(TFJS_SCRIPT_URL);

  if (!yamnetClasses) {
    const csv = await fetch(YAMNET_CLASS_MAP_URL).then((response) => response.text());
    yamnetClasses = csv
      .trim()
      .split('\n')
      .slice(1) // header row: index,mid,display_name
      .map((line) => {
        const firstComma = line.indexOf(',');
        const secondComma = line.indexOf(',', firstComma + 1);
        let name = line.slice(secondComma + 1).trim();
        if (name.startsWith('"') && name.endsWith('"')) name = name.slice(1, -1);
        return name;
      });
  }

  if (!yamnetModel) {
    yamnetModel = await window.tf.loadGraphModel(YAMNET_MODEL_URL, { fromTFHub: true });
  }

  return yamnetModel;
}

// Downsamples to the 16kHz mono waveform YAMNet expects. Simple linear
// interpolation - good enough for classification, not for playback quality.
function resampleTo16k(samples, fromRate) {
  if (fromRate === 16000) return samples;
  const ratio = fromRate / 16000;
  const outLength = Math.floor(samples.length / ratio);
  const out = new Float32Array(outLength);
  for (let i = 0; i < outLength; i += 1) {
    out[i] = samples[Math.floor(i * ratio)];
  }
  return out;
}

async function startSoundDetection(stream, onSoundEvent, onStatus) {
  try {
    await loadYamnet();
  } catch {
    onStatus('Sound detection model could not be loaded (check your connection).');
    return () => {};
  }

  const AudioContextImpl = window.AudioContext || window.webkitAudioContext;
  const ctx = new AudioContextImpl();
  const source = ctx.createMediaStreamSource(stream);
  // ScriptProcessorNode is deprecated in favour of AudioWorklet, but needs
  // no separate worklet module/build step - the right trade-off for a
  // no-build-step project. It still works in every current browser.
  const processor = ctx.createScriptProcessor(4096, 1, 1);

  let buffer = [];
  let bufferedSamples = 0;
  const targetSamples = ctx.sampleRate * (SOUND_WINDOW_MS / 1000);
  let busy = false;

  processor.onaudioprocess = async (event) => {
    if (!running) return;
    buffer.push(Float32Array.from(event.inputBuffer.getChannelData(0)));
    bufferedSamples += event.inputBuffer.length;
    if (bufferedSamples < targetSamples || busy) return;

    const merged = new Float32Array(bufferedSamples);
    let offset = 0;
    buffer.forEach((chunk) => {
      merged.set(chunk, offset);
      offset += chunk.length;
    });
    buffer = [];
    bufferedSamples = 0;
    busy = true;

    try {
      const waveform16k = resampleTo16k(merged, ctx.sampleRate);
      const meanScores = window.tf.tidy(() => {
        const waveformTensor = window.tf.tensor1d(waveform16k);
        const [scores] = yamnetModel.predict(waveformTensor);
        return scores.mean(0);
      });
      const scoresArray = await meanScores.array();
      meanScores.dispose();

      let best = { label: null, score: 0 };
      yamnetClasses.forEach((label, index) => {
        if (SOUND_LABEL_ALLOWLIST.has(label) && scoresArray[index] > best.score) {
          best = { label, score: scoresArray[index] };
        }
      });
      if (best.label && best.score >= SOUND_CONFIDENCE_THRESHOLD) {
        onSoundEvent({ label: best.label, confidence: best.score });
      }
    } catch {
      // One failed window should not stop monitoring - try the next one.
    } finally {
      busy = false;
    }
  };

  source.connect(processor);
  // A ScriptProcessorNode only fires onaudioprocess once connected to a
  // destination, even a silent one - required, not decorative.
  processor.connect(ctx.destination);

  return () => {
    processor.disconnect();
    source.disconnect();
    ctx.close();
  };
}

// ---------------------------------------------------------------------------
// 3. Facial expression (face-api.js)
// ---------------------------------------------------------------------------

async function loadFaceApi() {
  await loadScriptOnce(FACEAPI_SCRIPT_URL);
  const faceapi = window.faceapi;
  if (!faceapi.nets.tinyFaceDetector.isLoaded) {
    await faceapi.nets.tinyFaceDetector.loadFromUri(FACEAPI_MODELS_URL);
  }
  if (!faceapi.nets.faceExpressionNet.isLoaded) {
    await faceapi.nets.faceExpressionNet.loadFromUri(FACEAPI_MODELS_URL);
  }
  return faceapi;
}

/**
 * `videoEl` is supplied by the caller (incident.js) rather than created
 * here, so the camera preview is a real, visible element on the page - the
 * student can always see exactly what the camera sees, nothing runs on a
 * hidden feed.
 */
async function startFacialDetection(stream, videoEl, onFacialSignal, onStatus) {
  let faceapi;
  try {
    faceapi = await loadFaceApi();
  } catch {
    onStatus('Facial expression model could not be loaded (check your connection).');
    return () => {};
  }

  videoEl.srcObject = stream;
  videoEl.muted = true;
  videoEl.playsInline = true;
  await videoEl.play();

  const handle = setInterval(async () => {
    if (!running) return;
    try {
      const detection = await faceapi
        .detectSingleFace(videoEl, new faceapi.TinyFaceDetectorOptions())
        .withFaceExpressions();
      if (!detection) return;

      const { expressions } = detection;
      const distressScore = (expressions.fearful ?? 0)
        + (expressions.sad ?? 0)
        + (expressions.angry ?? 0) * 0.5;
      const [topLabel] = Object.entries(expressions).sort((a, b) => b[1] - a[1])[0];
      if (distressScore >= FACE_DISTRESS_THRESHOLD) {
        onFacialSignal({ label: topLabel, confidence: Math.min(distressScore, 1) });
      }
    } catch {
      // Skip this frame, try again on the next tick.
    }
  }, FACE_SAMPLE_MS);

  return () => clearInterval(handle);
}

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

/**
 * @param {object} options
 * @param {boolean} [options.withCamera] - also request the camera and run
 *   facial-expression detection. Audio (transcription + sound detection)
 *   always runs if the microphone permission is granted.
 * @param {HTMLVideoElement} [options.videoEl] - required when withCamera is
 *   true - the visible <video> element to preview the camera into.
 * @param {(text: string) => void} options.onTranscript
 * @param {(event: {label: string, confidence: number}) => void} options.onSoundEvent
 * @param {(event: {label: string, confidence: number}) => void} options.onFacialSignal
 * @param {(message: string) => void} [options.onStatus]
 * @returns {Promise<{ok: boolean, camera: boolean}>}
 */
export async function startDistressMonitoring({
  withCamera = false,
  videoEl = null,
  onTranscript = () => {},
  onSoundEvent = () => {},
  onFacialSignal = () => {},
  onStatus = () => {},
} = {}) {
  if (running) return { ok: true, camera: Boolean(camStream) };
  running = true;

  try {
    micStream = await navigator.mediaDevices.getUserMedia({ audio: true });
  } catch {
    running = false;
    onStatus('Microphone permission was not granted. Live listening is off.');
    return { ok: false, camera: false };
  }

  recognition = startTranscription(onTranscript, onStatus);
  const stopSound = await startSoundDetection(micStream, onSoundEvent, onStatus);
  stopHandlers = [stopSound];

  let cameraOn = false;
  if (withCamera && videoEl) {
    try {
      camStream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'user' } });
      const stopFace = await startFacialDetection(camStream, videoEl, onFacialSignal, onStatus);
      stopHandlers.push(stopFace);
      cameraOn = true;
    } catch {
      onStatus('Camera permission was not granted. Facial signal detection is off; audio listening continues.');
    }
  }

  onStatus('Listening…');
  return { ok: true, camera: cameraOn };
}

export function stopDistressMonitoring() {
  running = false;

  if (recognition) {
    try {
      recognition.onend = null;
      recognition.stop();
    } catch {
      // Already stopped.
    }
  }
  recognition = null;

  stopHandlers.forEach((stop) => {
    try {
      stop();
    } catch {
      // Already stopped.
    }
  });
  stopHandlers = [];

  if (micStream) {
    micStream.getTracks().forEach((track) => track.stop());
    micStream = null;
  }
  if (camStream) {
    camStream.getTracks().forEach((track) => track.stop());
    camStream = null;
  }
}

export function isDistressMonitoring() {
  return running;
}
