# OD-MAS: On-Device Multi-Agent Security

A privacy-first Android app that uses behavioral biometrics to detect when someone other than the device owner is using the phone. It learns the owner's patterns and challenges with biometrics when behavior seems unusual.

## 🎯 Overview

OD-MAS implements a multi-agent security system that continuously monitors user behavior through:
- **Touch dynamics** (pressure, velocity, curvature)
- **Motion sensors** (accelerometer, gyroscope)
- **Typing rhythm** (dwell/flight times)
- **App usage patterns**

The system uses two tiers of analysis:
- **Tier-0**: Fast statistical analysis using Mahalanobis distance
- **Tier-1**: Deep learning analysis using autoencoder reconstruction error

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

### Real-time Monitoring
- 3-second windows with 50% overlap
- Continuous sensor data collection
- Real-time risk assessment (0-100)

### Smart Escalation
- Escalate when risk > 75 for 5 consecutive windows OR > 85 once
- De-escalate when risk < 60 for 10 consecutive windows
- Trust credits system to prevent nagging

### Demo Mode
- 2-minute baseline establishment
- Guest simulation for testing
- Live risk visualization

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
- `HIGH_SAMPLING_RATE_SENSORS`: For motion detection
- `PACKAGE_USAGE_STATS`: For app usage monitoring (special access)
- `USE_BIOMETRIC`: For biometric verification
- `FOREGROUND_SERVICE`: For continuous monitoring

## 📊 Usage

### Initial Setup
1. Launch the app
2. Grant required permissions when prompted
3. Enable "Usage Access" in system settings
4. The system will establish a baseline over 2 minutes

### Normal Operation
- Use your device normally
- The app runs in background monitoring behavior
- Risk dial shows current security status
- Biometric prompt appears only when anomalies detected

### Demo Mode
1. Enable demo mode in settings
2. Establish baseline (2 minutes)
3. Hand device to another person
4. Watch risk level increase
5. Biometric verification will be triggered

## 🔧 Technical Details

### Fusion Algorithm
```
Tier-0: p₀ = 1 - CDF_χ²(d², df)
Tier-1: p₁ = Φ(z) where z = (e - μₑ)/σₑ
Chaquopy: c = Python ML behavioral analysis
Fusion: risk = 100 * (w₀*p₀ + w₁*p₁ + w₂*c)
Weights: w₀ = 0.3, w₁ = 0.2, w₂ = 0.5 (when Chaquopy confidence > 80%)
Fallback: w₀ = 0.7, w₁ = 0.3 (when Chaquopy not available)
```

### Policy Logic
- **Escalation**: Risk > 75 for 5 windows OR > 85 once
- **De-escalation**: Risk < 60 for 10 windows
- **Trust credits**: 3 total, decrement on yellow zone, restore every 30s

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
