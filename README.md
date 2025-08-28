# OD-MAS: On-Device Multi-Agent Security

A privacy-first Android app that uses behavioral biometrics to detect when someone other than the device owner is using the phone. It learns the owner's patterns and challenges with biometrics when behavior seems unusual.

## 🎯 Overview

OD-MAS implements a multi-agent security system that continuously monitors user behavior through:
- **Touch dynamics** (pressure, velocity, curvature)
- ~~Motion sensors (accelerometer, gyroscope)~~
- **Typing rhythm** (dwell/flight times)
- **App usage patterns**

The system uses two tiers of analysis:
- **Tier-0**: Fast statistical analysis using Mahalanobis distance (Touch, Typing only)
- **Tier-1**: Behavioral scoring models per modality (Touch, Typing)

## 🔒 Privacy Features

- **Zero cloud**: No internet permission, all data stays on-device
- **Content-free**: Only behavioral patterns, no text or content logging
- **Local storage**: Encrypted local storage only
- **User control**: Complete data deletion option

## 🏗️ Architecture

### Multi‑Agent System (on‑device)

```
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│   Tier-0 Agent  │    │   Tier-1 Agent  │    │  Fusion Agent   │
│   (Stats)       │    │   (Autoencoder) │    │                 │
│                 │    │                 │    │                 │
│ • Mahalanobis   │    │ • Reconstruction│    │ • Weighted      │
│ • Rolling stats │    │ • Error         │    │ • Bayesian      │
│ • Fast response │    │ • Deep learning │    │ • Risk fusion   │
└─────────────────┘    └─────────────────┘    └─────────────────┘
         │                       │                       │
         └───────────────────────┼───────────────────────┘
                                 │
                        ┌─────────────────┐
                        │  Policy Agent   │
                        │                 │
                        │ • Hysteresis    │
                        │ • Trust credits │
                        │ • Escalation    │
                        └─────────────────┘
                                 │
                        ┌─────────────────┐
                        │ Chaquopy Python │
                        │ ML              │
                        │                 │
                        │ • scikit-learn  │
                        │ • numpy/pandas  │
                        │ • ML Models     │
                        └─────────────────┘
```

### Core Components

- **SecurityManager**: Orchestrates all agents and Chaquopy Python ML
- **FusionAgent**: Combines Tier-0, Tier-1, and Chaquopy outputs
- **PolicyAgent**: Manages escalation and trust credits
- **SensorCollectors**: Capture touch and motion data
- **ChaquopyBehavioralManager**: Python ML behavioral analysis
- **ForegroundService**: Continuous monitoring

## 📱 Features

### Calibration Flow (POC Demo)
The app now includes a comprehensive calibration and testing workflow:

1. **Touch Calibration Phase**
   - Collect 30 touch samples from normal UI interactions
   - Minimize app and interact with system UI for real behavioral data
   - Real-time progress tracking and guidance

2. **Typing Calibration Phase** 
   - Type 100 characters using provided sentences
   - Inline text field with character counting
   - Comprehensive typing rhythm analysis

3. **Baseline Creation**
   - Automatic model training after calibration completion
   - Multi-tier agent preparation (Tier-0 stats, Tier-1 autoencoders)
   - Chaquopy Python ML model baseline establishment

4. **Test Mode**
   - Real-time behavioral risk monitoring (0-100%)
   - Live risk visualization with color-coded status
   - "End Test" functionality to stop monitoring

### Real-time Monitoring
- 3-second processing windows
- Continuous sensor data collection via AccessibilityService
- Real-time risk assessment with comprehensive logging

### Smart Escalation & Biometric Authentication
- **Full-screen biometric prompts** triggered by policy thresholds
- **Enhanced UI overlay** with semi-transparent background
- **Risk-based messaging** (Critical, High, Medium, Low risk reasons)
- **Proper session reset** - Risk resets to 0% after successful biometric verification
- **Trust credits system** (3 credits) to prevent excessive prompting

### Comprehensive Debugging
- **Detailed logging** throughout risk calculation pipeline
- **Policy decision tracking** with escalation/de-escalation reasoning
- **Biometric flow monitoring** with pre/post state comparisons
- **Fusion algorithm visibility** showing Tier-0, Tier-1, and Chaquopy contributions

## 🚀 Getting Started

### Prerequisites
- Android Studio Arctic Fox or later
- Android SDK 24+ (API level 24)
- Device with accelerometer and gyroscope sensors

### Installation

1. Clone the repository:
```bash
git clone https://github.com/your-username/odmas.git
cd odmas
```

2. Open in Android Studio and sync project

3. Build and run on device:
```bash
./gradlew assembleDebug
```

### Permissions

The app requires these permissions:
- `PACKAGE_USAGE_STATS`: For app usage monitoring (special access)
- `USE_BIOMETRIC` / `BIOMETRIC_WEAK`: For biometric verification
- `FOREGROUND_SERVICE`: For continuous monitoring
- `POST_NOTIFICATIONS`: For heads-up verify action
- `SYSTEM_ALERT_WINDOW`: To keep accessibility overlay sticky

## 📊 Usage

### Step-by-Step Calibration Flow

#### 1. Initial Setup
1. **Launch the app** and grant required permissions when prompted
2. **Enable permissions**:
   - Touch/Accessibility Service access in system settings
   - Usage Access permission
   - Biometric authentication setup
   - Notification permissions

#### 2. Calibration Process
1. **Start Calibration**: Tap "Start POC Demo" to begin the calibration flow

2. **Touch Calibration Phase** (30 samples required):
   - Read the instructions: "Minimize app and use normal UI"
   - Tap "Minimize App" to switch to system UI
   - **Interact normally** with your device - scroll, tap icons, navigate menus
   - **Return to app** when you have 30+ touch samples
   - Progress is tracked in real-time via AccessibilityService

3. **Typing Calibration Phase** (100 characters required):
   - Use the inline text field to type provided sentences
   - **Type naturally** - don't try to type differently
   - Character count updates in real-time: "Typed: X/100"
   - Typing rhythm and dwell/flight times are analyzed

4. **Baseline Creation** (automatic):
   - Models are trained automatically after typing completion
   - Tier-0 statistical baselines established
   - Tier-1 autoencoder models prepared  
   - Chaquopy Python ML baseline created
   - Wait for "Baseline Ready" status

5. **Test Mode** (manual start):
   - Tap "Start Test" to begin behavioral monitoring
   - **Use device normally** or hand to another person
   - Watch the risk meter respond to behavioral changes
   - Risk level updates every 3 seconds (0-100%)
   - **Biometric prompt** appears when risk exceeds thresholds
   - Tap "End Test" when finished

#### 3. Understanding Risk Levels
- **Green (0-59%)**: Normal behavior, low risk
- **Yellow (60-74%)**: Slight behavioral anomaly  
- **Orange (75-84%)**: High risk, approaching escalation
- **Red (85-100%)**: Critical risk, biometric verification required

#### 4. Biometric Authentication
- **Automatic triggers** when risk crosses policy thresholds
- **Full-screen overlay** with semi-transparent background
- **Risk-appropriate messaging** explaining why verification is needed
- **Success**: Risk resets to 0%, trust credits restored
- **Failure/Cancel**: Prompt remains visible for retry

### Production Operation
- The calibrated system runs continuously in the background
- **TouchAccessibilityService** monitors all touch interactions system-wide
- **Real-time analysis** processes behavior every 3 seconds
- **Intelligent escalation** prevents false positives with trust credits
- **Privacy-first**: All processing happens on-device, no cloud connectivity

## 🔧 Technical Details

### Fusion Algorithm (Updated Implementation)
```
Tier-0: Mahalanobis distance → normalized to [0,1] → averaged across modalities
Tier-1: Autoencoder reconstruction error → probability via z-score normalization  
Chaquopy: Python ML analysis (Isolation Forest, One-Class SVM) → risk percentage

Fusion Strategy:
- When Chaquopy confidence > 80%: 
  sessionRisk = 0.5 * fusedRisk + 0.5 * chaquopyRisk
- When Chaquopy confidence ≤ 80%:
  sessionRisk = fusedRisk only

fusedRisk = weight_tier0 * tier0Risk + weight_tier1 * tier1Risk
Weights: Tier-0 = 0.2, Tier-1 = 0.8 (favoring deep learning analysis)
```

### Policy Logic (Enhanced)
- **Escalation Thresholds**:
  - **Critical**: >85% → Immediate biometric prompt
  - **High**: >75% for 5 consecutive windows → Biometric prompt
  - **Trust Credits**: Depleted at 60-75% risk (yellow zone)
- **De-escalation**: <60% risk for 10 consecutive windows
- **Trust Credits**: 3 total, restore 1 every 30 seconds when risk <60%
- **Session Reset**: Risk → 0% after successful biometric verification
- **Comprehensive Logging**: All policy decisions tracked with detailed reasoning

### Performance
- **TTE (Time-to-Escalation)**: < 90 seconds
- **False prompt rate**: ≤ 1 per 30 minutes
- **P95 UI frame time**: Smooth performance

## 🧪 Testing

### Unit Tests
```bash
./gradlew test
```

### Integration Tests
```bash
./gradlew connectedAndroidTest
```

### Performance Tests
```bash
./gradlew benchmark
```

## 📈 Performance Monitoring

The app includes:
- **Macrobenchmark**: Startup and UI performance
- **Baseline Profiles**: Faster startup
- **JankStats**: Frame drop monitoring

## 🔐 Security Considerations

- No internet connectivity
- Local data encryption
- Minimal permissions required
- Transparent data collection
- Python ML behavioral analysis via Chaquopy

## 🔄 Chaquopy Python ML Integration

The app integrates with **Chaquopy** for Python-based behavioral biometrics analysis:

### Features
- **Python ML Libraries**: scikit-learn, numpy, pandas, scipy
- **Advanced ML Models**: Isolation Forest, One-Class SVM
- **Real-time Analysis**: Continuous behavioral monitoring
- **Privacy-First**: Offline analysis, no data transmission
- **High Accuracy**: Professional ML algorithms for anomaly detection

### Integration Status
- ✅ **Python Manager**: Complete Chaquopy integration framework
- ✅ **Security System**: Integrated with multi-agent fusion
- ✅ **UI Monitoring**: Real-time behavioral data display
- ✅ **ML Models**: Isolation Forest and One-Class SVM implemented

### Getting Started
1. **Chaquopy License**: Get Chaquopy license (free for development)
2. **Python Packages**: ML libraries automatically installed via pip
3. **Ready to Use**: All Python ML functionality implemented
4. **Test Integration**: Build and test the app

### Python ML Libraries Used
- **scikit-learn**: Isolation Forest, One-Class SVM
- **numpy**: Numerical computing and array operations
- **pandas**: Data processing and analysis
- **scipy**: Statistical analysis and signal processing
- User-controlled data deletion

## 🗂 Branches / Reference App

- The `OD-MAS-modifications` directory in this repo is a reference snapshot used during integration. It is ignored by git and not part of the build. The current codebase reflects those mechanics integrated into the main app.

## 🤝 Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Add tests
5. Submit a pull request

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## 🙏 Acknowledgments

- Android Biometric API
- TensorFlow Lite for on-device ML
- Material Design 3 for UI components
- Behavioral biometrics research community

## 📞 Support

For questions or issues:
- Create an issue on GitHub
- Check the documentation
- Review the architecture guide

---

**OD-MAS**: Privacy-first behavioral security for Android devices.
