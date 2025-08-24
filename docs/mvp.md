# Page 1 — MVP Overview

## Product name

**OD-MAS** — On-Device Multi-Agent Security

## One-liner

Privacy-first, on-device behavioral anomaly detection that continuously learns your usage patterns (touch, typing rhythm, motion, app usage) and gently challenges with biometrics when something looks "not you." No cloud, no third-party SDKs.

## Problem

Passwords/biometrics protect the lock screen, but once unlocked, devices are vulnerable to shoulder-surfing, theft, or casual misuse. Owners and "guests" often use the same apps—what differs is **how** they interact. We need a lightweight, always-local system that learns *your* behavior and flags anomalies in real time.

## Goals (Phase-1)

* **Real-time session risk** (0–100) updated from local behavioral signals.
* **Seamless UX:** only prompt with **BiometricPrompt** when risk crosses a high threshold; otherwise stay invisible. ([Android Developers][1], [Android Developers Blog][2])
* **Zero cloud:** ship without `INTERNET` permission; local storage only.
* **Judge-ready Demo Mode:** 2-min auto-baseline → hand to guest → risk rises → biometric verify, back to green.

## Non-goals (Phase-1)

* No server, no third-party APIs/SDKs.
* No content logging (keyboard captures *timings only*).
* No population data or centralized learning.

## Users & key stories

* **Owner:** "Use phone normally; be challenged only when behavior is unusual."
* **Guest:** "When I use their phone, the system should notice and ask for verify."
* **Judge:** "Fresh install → quick baseline → hand-over → visible risk change → verify."

## Success metrics (MVP demo)

* **TTE (time-to-escalation)**: < 90 s from guest interaction to verify prompt.
* **False prompt rate**: ≤ 1 per 30 min owner-only session.
* **P95 UI frame time**: smooth (no visible jank) on dashboard interactions — validated with **Macrobenchmark**/**JankStats**. ([Android Developers][3])

---

# Page 2 — Scope, Architecture & Data

## MVP Scope (what we will ship)

1. **Signals (local, standard APIs)**

* **Touch dynamics** (inside our app views): x/y, size, normalized pressure, velocity/curvature, tap dwell via `MotionEvent`. ([Android Developers][4], [android.googlesource.com][5])
* **Typing rhythm** (custom keyboard): **dwell** & **flight** times via `InputMethodService`. (Behavioral biometrics literature defines these as core features.) ([PMC][6])
* **Motion (IMU):** accelerometer + gyroscope via `SensorManager` (orientation deltas, micro-tremor). ([Android Developers][7])
* **App usage transitions:** foreground app stats via `UsageStatsManager` (with user-granted **Special App Access**). ([Android Developers][8])

2. **Modeling (on-device)**

* **Tier-0 Stats Agent (fast):** rolling mean/cov; **Mahalanobis** distance per 3–5 s window → normalized event risk; **session EMA** smooths spikes.
* **Tier-1 AE Agent (smart):** tiny int8 **autoencoder** (1D over time×features) via **TensorFlow Lite (LiteRT)** or **ONNX Runtime Mobile**; reconstruction error → risk. ([Google AI for Developers][9], [ONNX Runtime][10])
* **Fusion Agent:** weighted/Bayesian fusion of Tier-0 + Tier-1 → session risk.

3. **Policy & actions**

* **Policy Agent:** hysteresis + "trust credits" to avoid nagging.
* **High risk → Verify:** show bottom-sheet with **BiometricPrompt**; on success, decay risk to baseline. ([Android Developers][1])

4. **Runtime & background**

* **Foreground service type (Android 14+)** if continuous capture is needed; otherwise WorkManager for periodic housekeeping. ([Android Developers][11])
* **No INTERNET**; encrypted local storage; explicit consent for Usage Access & custom IME.

## Architecture (modules)

```
/app               Compose UI (Material 3), Navigation, Verify sheet
/core-sensors      Touch (MotionEvent), IMU (SensorManager), Usage (UsageStats)
/core-ml           Feature windows, normalization, ModelRunner (TFLite/ONNX)
/core-agents       Context, Stats (Tier-0), AE (Tier-1), Fusion, Policy, Energy
/core-data         DataStore + Room (local-only)
/demo-kit          2-min baseline wizard, guest toggle, live risk chart
/benchmark-macro   Macrobenchmark (startup, scroll, animation)
/baselineprofile   Baseline Profiles for faster startup & smoothness
/docs              mvp.md, architecture.md, evaluation.md, ethics.md
```

## Key OS/ML references (judge-friendly)

* **UsageStatsManager** for app usage/foreground history. ([Android Developers][8])
* **MotionEvent** touch attributes (size/pressure/coords/history). ([Android Developers][4])
* **Sensors:** accelerometer/gyroscope overview. ([Android Developers][7])
* **BiometricPrompt** for system auth UI. ([Android Developers][1])
* **Foreground service types (Android 14+)**. ([Android Developers][11])
* **WorkManager** for robust background scheduling. ([Android Developers][12])
* **Material 3 in Compose** for premium, consistent UI. ([Material Design][13], [Android Developers][14])
* **Macrobenchmark / Baseline Profiles / JankStats** for performance proof. ([Android Developers][3])
* **On-device ML:** TensorFlow Lite (LiteRT) & ONNX Runtime Mobile. ([Google AI for Developers][9], [ONNX Runtime][10])
* **Keystroke dynamics** as behavioral biometrics (dwell/flight). ([PMC][6])

---

# Page 3 — UX, Demo, Testing, Ethics & Plan

## UX (Compose, Material 3)

* **Top bar:** "OD-MAS" + privacy badge "On-device · No Cloud".
* **Risk Dial** (animated), **Status Chip** ("All good / Investigating / Verify"), **mini EMA chart**, **Timeline** (per-agent deltas).
* **Verify bottom sheet** → system **BiometricPrompt**; gentle haptics; clear reason ("Touch cadence shifted; confirm it's you"). ([Material Design][13])

## Demo flow (Phase-1 video)

1. Fresh install → onboarding → grant Usage Access and enable OD-MAS Keyboard (timings only).
2. **Auto-baseline (2 min):** interact naturally; risk stays green.
3. **Hand to guest (60–90 s):** scrolling/typing shows risk rising; **Verify** appears; biometric success → back to green.
4. Tap **Explain** chip → show top contributors (e.g., typing flight variance, swipe velocity).

## Testing & performance

* **Instrument performance**:

  * **Baseline Profiles** to precompile hot paths (ship with release). ([Android Developers][15])
  * **Macrobenchmark** for startup/scroll/animation. ([Android Developers][3])
  * **JankStats** logs for frame drops; attach summary in `/docs/evaluation.md`. ([Android Developers][16])
* **Functional checks**:

  * Usage Access onboarding and denial paths. ([Android Developers][8])
  * BiometricPrompt retries/cancellation. ([Android Developers][1])
  * Foreground service notification (if used) and Android 14 **service type** correctness. ([Android Developers][17])
* **Model sanity**:

  * Tier-0 threshold from χ² quantiles on baseline;
  * Tier-1 AE reconstruction error percentile → fuse with Tier-0;
  * Record **TTE**, **false prompts**, and CPU/Battery snapshots.

## Ethics & privacy (Phase-1)

* **No data leaves device**; no `INTERNET` permission; clear Data Deletion setting.
* **Minimum necessary permissions**; friendly Special App Access explainer for `PACKAGE_USAGE_STATS`. ([Android Developers][8])
* **Content-free IME:** collect **dwell/flight** only (no text). Keystroke dynamics is established as timing-based behavioral biometrics. ([PMC][6])
* **Accessibility & inclusivity:** adjustable sensitivity; "large-touch" mode; opt-out per signal.

## Delivery checklist (what to implement in 3 days)

**Day 1:**

* Compose shell (Risk Dial, Status chip, mini chart, Timeline).
* Touch + IMU collectors; **Tier-0 Stats Agent** + session EMA → live risk.
* Demo Mode wizard & Usage Access flow. ([Android Developers][8])

**Day 2:**

* Custom **IME** for dwell/flight timings.
* **BiometricPrompt** verify sheet + Policy Agent (hysteresis/trust). ([Android Developers][1])
* **Foreground service type** if continuous capture needed; otherwise WorkManager periodic tasks. ([Android Developers][17])

**Day 3:**

* Tiny AE model (int8) via **LiteRT / ONNX Runtime Mobile**; **Fusion Agent**. ([Google AI for Developers][9], [ONNX Runtime][10])
* Performance pass: **Baseline Profiles**, **Macrobenchmark**, **JankStats**; write `/docs/evaluation.md` with results. ([Android Developers][15])
* Final polish: Explainability chip; Delete Local Data; screenshots for README.

---

### Appendix — API quick links

* UsageStatsManager (special access) — foreground/app history. ([Android Developers][8])
* Motion sensors (accelerometer/gyro) — SensorManager overview. ([Android Developers][7])
* MotionEvent — touch size/pressure/coords. ([Android Developers][4])
* BiometricPrompt — system biometric dialog. ([Android Developers][1])
* Foreground service types (Android 14+). ([Android Developers][11])
* WorkManager — robust background scheduling. ([Android Developers][12])
* Material 3 in Compose — components & theming. ([Material Design][13], [Android Developers][14])
* Macrobenchmark / Baseline Profiles / JankStats — perf tooling. ([Android Developers][3])
* On-device ML runtimes — LiteRT (TensorFlow Lite) / ONNX Runtime Mobile. ([Google AI for Developers][9], [ONNX Runtime][10])

---

[1]: https://developer.android.com/reference/android/hardware/biometrics/BiometricPrompt?utm_source=chatgpt.com "BiometricPrompt | API reference"
[2]: https://android-developers.googleblog.com/2019/10/one-biometric-api-over-all-android.html?utm_source=chatgpt.com "One Biometric API Over all Android"
[3]: https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview?utm_source=chatgpt.com "Write a Macrobenchmark | App quality"
[4]: https://developer.android.com/reference/kotlin/android/view/MotionEvent?utm_source=chatgpt.com "MotionEvent | API reference"
[5]: https://android.googlesource.com/platform/frameworks/base/%2B/refs/heads/main/core/java/android/view/MotionEvent.java?utm_source=chatgpt.com "core/java/android/view/MotionEvent.java - platform/frameworks/base - Git at Google"
[6]: https://pmc.ncbi.nlm.nih.gov/articles/PMC3835878/?utm_source=chatgpt.com "A Survey of Keystroke Dynamics Biometrics - PMC"
[7]: https://developer.android.com/develop/sensors-and-location/sensors/sensors_motion?utm_source=chatgpt.com "Motion sensors | Sensors and location"
[8]: https://developer.android.com/reference/android/app/usage/UsageStatsManager?utm_source=chatgpt.com "UsageStatsManager | API reference"
[9]: https://ai.google.dev/edge/litert?utm_source=chatgpt.com "LiteRT overview | Google AI Edge - Gemini API"
[10]: https://onnxruntime.ai/docs/get-started/with-mobile.html?utm_source=chatgpt.com "Mobile | onnxruntime"
[11]: https://developer.android.com/about/versions/14/changes/fgs-types-required?utm_source=chatgpt.com "Foreground service types are required"
[12]: https://developer.android.com/topic/libraries/architecture/workmanager?utm_source=chatgpt.com "Data Layer - Schedule Task with WorkManager - ..."
[13]: https://m3.material.io/develop/android/jetpack-compose?utm_source=chatgpt.com "Material Design 3 for Jetpack Compose"
[14]: https://developer.android.com/jetpack/androidx/releases/compose-material3?utm_source=chatgpt.com "Compose Material 3 | Jetpack"
[15]: https://developer.android.com/topic/performance/baselineprofiles/overview?utm_source=chatgpt.com "Baseline Profiles overview | App quality"
[16]: https://developer.android.com/topic/performance/jankstats?utm_source=chatgpt.com "JankStats Library | App quality"
[17]: https://developer.android.com/develop/background-work/services/fgs/service-types?utm_source=chatgpt.com "Foreground service types | Background work"
