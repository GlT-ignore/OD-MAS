# OD-MAS: On-Device Multi‑Agent Security

A privacy‑first Android app that uses behavioral biometrics to detect when someone other than the device owner is using the device. It learns the owner’s patterns and challenges with biometrics when behavior seems unusual.

This README reflects the latest codebase after recent functional changes: staged calibration (Motion → Touch → Typing), live window stats in the UI, robust Tier‑0 statistics, accessibility‑based touch generation, dwell‑weighted Touch Tier‑1 scoring, broadcast hardening for Android 13+, and safe notification channel handling for foreground services.

## Overview

OD‑MAS monitors behavior through:
- Touch dynamics (pressure, size, dwell/flight, speed, curvature, rhythm)
- Motion sensors (accelerometer and gyroscope features)
- Optional hybrid behavioral analysis via Chaquopy (Python)

Two tiers of on‑device analysis:
- Tier‑0 (Stats): rolling mean/covariance, Mahalanobis distance d², mapped with χ² lower‑tail CDF to [0,1]
- Tier‑1 (Behavioral, pure Kotlin): per‑modality models (Motion/Touch/Typing) calibrated via coverage and scored to probability [0,1]

Fusion produces a session risk on a unified 0–100 scale with EMA smoothing.

Key agents and orchestrator:
- [SecurityManager](OD-MAS/app/src/main/java/com/example/odmas/core/SecurityManager.kt): orchestration, state, policy, escalation
- [Tier0StatsAgent](OD-MAS/app/src/main/java/com/example/odmas/core/agents/Tier0StatsAgent.kt): rolling buffers/baselines and d²
- Behavioral Tier‑1 (pure Kotlin):
  - Motion/Touch/Typing scoring in [Tier1BehaviorAgent](OD-MAS/app/src/main/java/com/example/odmas/core/agents/Tier1BehaviorAgent.kt)
- [FusionAgent](OD-MAS/app/src/main/java/com/example/odmas/core/agents/FusionAgent.kt): gating and risk fusion (0–100)
- [PolicyAgent](OD-MAS/app/src/main/java/com/example/odmas/core/agents/PolicyAgent.kt): escalation, hysteresis, trust credits
- Foreground service SSOT: [SecurityMonitoringService](OD-MAS/app/src/main/java/com/example/odmas/core/services/SecurityMonitoringService.kt)

## What’s New (August 2025 continuation)

Functional
- Staged calibration flow (coverage‑based, not fixed time)
  - Security state exposes a stage (MOTION → TOUCH → TYPING → COMPLETE) with per‑stage counters
  - UI shows stage‑specific instructions and progress
  - Source: [SecurityManager.updateSecurityState()](OD-MAS/app/src/main/java/com/example/odmas/core/SecurityManager.kt:398), [MainScreen](OD-MAS/app/src/main/java/com/example/odmas/ui/screens/MainScreen.kt)
- Live “feature bounds” table now reads current 3s window stats (not stale running stats)
  - Robust to malformed samples and single‑sample windows
  - Source: [Tier0StatsAgent.getWindowStatsFor()](OD-MAS/app/src/main/java/com/example/odmas/core/agents/Tier0StatsAgent.kt:189), [SecurityManager.updateSecurityState()](OD-MAS/app/src/main/java/com/example/odmas/core/SecurityManager.kt:398)
- Accessibility‑based touch generation (system‑wide)
  - TYPE_TOUCH_INTERACTION_START/END measures dwell; we build a consistent 10‑D touch vector aligned to sensor mapping, with slight variability
  - Fallback for TYPE_VIEW_CLICKED and system interactions
  - Source: [TouchAccessibilityService.onAccessibilityEvent](OD-MAS/app/src/main/java/com/example/odmas/core/services/TouchAccessibilityService.kt:119), [TouchAccessibilityService.buildA11yTouchFeatures()](OD-MAS/app/src/main/java/com/example/odmas/core/services/TouchAccessibilityService.kt:358)
- Tier‑1 Touch scoring made dwell‑weighted and sensitive to small feature variations
  - Dwell dominates with softer contributions from pressure/size; optional rhythm if valid
  - Source: [Tier1BehaviorAgent.scoreTouch()](OD-MAS/app/src/main/java/com/example/odmas/core/agents/Tier1BehaviorAgent.kt:366)
- Tier‑0 robustness and gating
  - Per‑modality minimum window samples to avoid spikes on sparse data
  - Robust window stats (skip malformed/short vectors)
  - Source: [Tier0StatsAgent.getWindowStatsFor()](OD-MAS/app/src/main/java/com/example/odmas/core/agents/Tier0StatsAgent.kt:189), [Tier0StatsAgent.computeMean()](OD-MAS/app/src/main/java/com/example/odmas/core/agents/Tier0StatsAgent.kt:255), [Tier0StatsAgent.computeCovarianceMatrix()](OD-MAS/app/src/main/java/com/example/odmas/core/agents/Tier0StatsAgent.kt:262)

Platform/policy
- Android 13+ BroadcastReceiver flags
  - Overlay control receiver registered with RECEIVER_NOT_EXPORTED on API 33+
  - Source: [TouchAccessibilityService.registerReceiver](OD-MAS/app/src/main/java/com/example/odmas/core/services/TouchAccessibilityService.kt:85)
- Foreground notification channel safety
  - Do not delete a channel while an FG service is active; update/create only
  - Source: [SecurityMonitoringService.createNotificationChannel()](OD-MAS/app/src/main/java/com/example/odmas/core/services/SecurityMonitoringService.kt:111)

Diagnostics
- Detailed Tier‑1 per‑modality logs on event and periodic runs
- “Table freeze” and “flat touch probability” mitigations documented below

## Architecture

Runtime SSOT (service‑owned):
- [SecurityMonitoringService](OD-MAS/app/src/main/java/com/example/odmas/core/services/SecurityMonitoringService.kt) owns [SecurityManager](OD-MAS/app/src/main/java/com/example/odmas/core/SecurityManager.kt), motion collection, and receivers for touch/typing/commands.
- UI observes state via DataStore and emits commands/events via in‑app, package‑scoped broadcasts.

Data flow (simplified):
1) Sensors/Services produce 10‑D feature vectors per modality
   - Motion: [MotionSensorCollector](OD-MAS/app/src/main/java/com/example/odmas/core/sensors/MotionSensorCollector.kt)
   - Touch: in‑app synthetic via [SecurityViewModel](OD-MAS/app/src/main/java/com/example/odmas/viewmodels/SecurityViewModel.kt), A11y via [TouchAccessibilityService](OD-MAS/app/src/main/java/com/example/odmas/core/services/TouchAccessibilityService.kt)
   - Typing: dwell/flight via A11y or UI fallback
2) [Tier0StatsAgent](OD-MAS/app/src/main/java/com/example/odmas/core/agents/Tier0StatsAgent.kt) updates rolling buffers, computes per‑modality baselines, d² and window stats
3) [Tier1BehaviorAgent](OD-MAS/app/src/main/java/com/example/odmas/core/agents/Tier1BehaviorAgent.kt) scores per modality to [0,1] probability (coverage‑gated)
4) [FusionAgent](OD-MAS/app/src/main/java/com/example/odmas/core/agents/FusionAgent.kt) fuses Tier‑0, Tier‑1 (and optionally Chaquopy) to 0–100 risk with EMA
5) [PolicyAgent](OD-MAS/app/src/main/java/com/example/odmas/core/agents/PolicyAgent.kt) decides escalation/de‑escalation
6) [SecurityManager](OD-MAS/app/src/main/java/com/example/odmas/core/SecurityManager.kt) updates state (counters, bounds CSV, readiness flags, calibration stage), drives overlay broadcasts and UI DataStore updates

## Calibration and Stages

- Staged coverage (not fixed 2 minutes):
  - Motion: N windows (~3s each)
  - Touch: N taps (A11y/system‑wide or in‑app taps)
  - Typing: N keys (A11y key change inference; in‑app fallback emits synthetic typing)
- State fields:
  - calibrationStage (MOTION/TOUCH/TYPING/COMPLETE)
  - motionCount/touchCount/typingCount and targets
  - readiness flags (tier0Ready, tier1Ready)
- UI:
  - Stage‑specific guidance and counters
  - Typing stage auto‑focuses an input and emits synthetic typing fallback
- Implementation:
  - [SecurityManager.updateSecurityState()](OD-MAS/app/src/main/java/com/example/odmas/core/SecurityManager.kt:379)
  - [MainScreen BaselineCard](OD-MAS/app/src/main/java/com/example/odmas/ui/screens/MainScreen.kt)

## Feature Table (live window stats)

- The “Running feature bounds (mean ± σ)” table now reflects the latest 3s window per modality, not long‑horizon running stats
- Robust computation (skips malformed vectors) and single‑sample windows are allowed (std=0 → displayed)
- Plumbing:
  - [Tier0StatsAgent.getWindowStatsFor()](OD-MAS/app/src/main/java/com/example/odmas/core/agents/Tier0StatsAgent.kt:189)
  - [SecurityManager.formatBoundsAll()](OD-MAS/app/src/main/java/com/example/odmas/core/SecurityManager.kt:442)
  - [SecurityViewModel.parseBounds](OD-MAS/app/src/main/java/com/example/odmas/viewmodels/SecurityViewModel.kt:287)

## Touch Generation (system‑wide and in‑app)

- Accessibility service (global)
  - Measures dwell from TYPE_TOUCH_INTERACTION_START/END
  - Builds a consistent 10‑D touch vector aligned to the app’s feature contract
  - Adds slight pressure/size variability and speed∝1/dwell for realistic dynamics
  - Sources:
    - [TouchAccessibilityService.onAccessibilityEvent](OD-MAS/app/src/main/java/com/example/odmas/core/services/TouchAccessibilityService.kt:119)
    - [TouchAccessibilityService.buildA11yTouchFeatures()](OD-MAS/app/src/main/java/com/example/odmas/core/services/TouchAccessibilityService.kt:358)
- In‑app touch (Compose)
  - Synthetic events forwarded via [SecurityViewModel.sendSyntheticTouchFeatures](OD-MAS/app/src/main/java/com/example/odmas/viewmodels/SecurityViewModel.kt)
- Both paths end up as 10‑D vectors via package‑scoped broadcasts to the service receiver

## Tier‑0 (Stats) details

- Per‑modality buffers and baselines (mean/covariance)
- Mahalanobis d² → lower‑tail χ² CDF to anomaly probability p₀ in [0,1]
  - Source: [FusionAgent.processTier0Risk()](OD-MAS/app/src/main/java/com/example/odmas/core/agents/FusionAgent.kt:57)
- Robustness and gating
  - Minimum recent samples per modality before computing d²
  - Window stats skip malformed/short vectors
  - Sources:
    - [Tier0StatsAgent.getWindowStatsFor()](OD-MAS/app/src/main/java/com/example/odmas/core/agents/Tier0StatsAgent.kt:189)
    - [Tier0StatsAgent.computeMean()](OD-MAS/app/src/main/java/com/example/odmas/core/agents/Tier0StatsAgent.kt:255)
    - [Tier0StatsAgent.computeCovarianceMatrix()](OD-MAS/app/src/main/java/com/example/odmas/core/agents/Tier0StatsAgent.kt:262)

## Tier‑1 (Behavioral) details

- Coverage‑based calibration (per modality)
- Probability scoring to [0,1] (higher → more anomalous)
- Touch scoring with dwell emphasis and guarded rhythm term
  - Source: [Tier1BehaviorAgent.scoreTouch()](OD-MAS/app/src/main/java/com/example/odmas/core/agents/Tier1BehaviorAgent.kt:366)
- Motion/Typing scoring also handled in the same agent

## Fusion and Session Risk

- Gating: run Tier‑1 if Tier‑0 probability exceeds 0.30 or periodically (every ~10s)
- Fusion: risk = 100 × (w₀ × p₀ + (1−w₀) × p₁′)
  - Early session w₀ = 0.7, then 0.5; EMA smoothing applied
  - p₁′ falls back to p₀ if Tier‑1 unavailable
  - Chaquopy optional blend at high confidence is supported by [SecurityManager](OD-MAS/app/src/main/java/com/example/odmas/core/SecurityManager.kt)
- Source: [FusionAgent.fuseRisks()](OD-MAS/app/src/main/java/com/example/odmas/core/agents/FusionAgent.kt:82)

## Escalation and Overlay

- Policy thresholds (hysteresis with trust credits) trigger biometric challenge
- Background overlay (Accessibility) prompts “Verify identity” and brings the app to foreground for biometric prompt
- Android 13+ receiver flags:
  - [TouchAccessibilityService.registerReceiver (NOT_EXPORTED)](OD-MAS/app/src/main/java/com/example/odmas/core/services/TouchAccessibilityService.kt:85)
- Foreground notification channel safety:
  - [SecurityMonitoringService.createNotificationChannel()](OD-MAS/app/src/main/java/com/example/odmas/core/services/SecurityMonitoringService.kt:111)

## Getting Started

Prerequisites
- Android Studio (Giraffe+ recommended)
- API level 24+ device with accelerometer/gyroscope

Build and install
```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

First launch
- Grant runtime permissions (Sensors, Notifications on Android 13+)
- Grant “Usage Access”
- Enable the “Touch Accessibility Service” in system settings

Calibration flow
- Follow on‑screen guidance:
  - Motion: hold steady (N windows)
  - Touch: tap/drag naturally around the screen (N taps)
  - Typing: type in the in‑app field (N keys)
- The progress card shows stage, counters, and “mean ± σ” bounds
- Once complete, monitoring and scoring begin continuously

## Troubleshooting

- Feature table not updating
  - Ensure touch events are reaching the service:
    - Watch Logcat for “Sending touch features to security manager” from A11y or UI
  - The table uses live window stats and updates with any valid sample; std shows 0 on single‑sample windows
  - Robust stats skip malformed vectors; if none valid, the table temporarily blanks (no crash)

- Tier‑1 Touch probability appears flat
  - The A11y path now measures dwell and introduces slight variability in pressure/size and speed
  - Touch Tier‑1 emphasizes dwell z‑score; expect score movement with different tap cadences/pressures

- High Tier‑0 risk “1.0” with large d²
  - This reflects significant deviation from the learned Motion baseline (common if posture/environment differ)
  - Options to soften Tier‑0: raise min samples per window, compress p₀ with a transform, or reduce early Tier‑0 weight in fusion

- Overlay receiver warning on Android 13+
  - Fixed by using RECEIVER_NOT_EXPORTED for overlay control broadcasts (package‑scoped)

- Foreground service channel SecurityException
  - Fixed by not deleting an existing channel; we only (re)create or update allowable fields

## Developer Notes

Important entry points and references:
- Orchestrator: [SecurityManager](OD-MAS/app/src/main/java/com/example/odmas/core/SecurityManager.kt)
  - Live window table wiring: [updateSecurityState](OD-MAS/app/src/main/java/com/example/odmas/core/SecurityManager.kt:398)
- Tier‑0 stats and robustness:
  - [Tier0StatsAgent.getWindowStatsFor](OD-MAS/app/src/main/java/com/example/odmas/core/agents/Tier0StatsAgent.kt:189)
  - [Tier0StatsAgent.computeMean](OD-MAS/app/src/main/java/com/example/odmas/core/agents/Tier0StatsAgent.kt:255)
  - [Tier0StatsAgent.computeCovarianceMatrix](OD-MAS/app/src/main/java/com/example/odmas/core/agents/Tier0StatsAgent.kt:262)
- Tier‑1 scoring (Touch dwell‑weighted): [Tier1BehaviorAgent.scoreTouch](OD-MAS/app/src/main/java/com/example/odmas/core/agents/Tier1BehaviorAgent.kt:366)
- Fusion χ² mapping and EMA: [FusionAgent.processTier0Risk](OD-MAS/app/src/main/java/com/example/odmas/core/agents/FusionAgent.kt:57), [FusionAgent.fuseRisks](OD-MAS/app/src/main/java/com/example/odmas/core/agents/FusionAgent.kt:82)
- Accessibility broadcast hardening: [TouchAccessibilityService.registerReceiver](OD-MAS/app/src/main/java/com/example/odmas/core/services/TouchAccessibilityService.kt:85)
- Foreground notification channel safety: [SecurityMonitoringService.createNotificationChannel](OD-MAS/app/src/main/java/com/example/odmas/core/services/SecurityMonitoringService.kt:111)

## Privacy

- No internet permission
- Local, encrypted storage
- Only behavior metrics (no contents/text)
- On‑device analysis (Kotlin and optional Python)

## License

This project is licensed under the MIT License – see [LICENSE](LICENSE).

## Support

- File issues on the repository
- Include logs (filesDir/odmas.log) and device/OS details
- Reference relevant code links by line where possible
