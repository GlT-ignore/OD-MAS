# Development Log: Building the OD-MAS Calibration Flow

This document details the development journey and major improvements made to get the OD-MAS behavioral biometrics app working properly.

## The Starting Point

The basic framework was in place but the user experience was fundamentally broken. The most frustrating issue was that after calibration, risk would immediately spike to 90%+ making the system unusable. Something was fundamentally wrong with the risk calculation pipeline.

## Building the Complete Calibration Flow

The app needed a proper end-to-end demo experience. Here's the calibration process that was designed:

### Phase 1: Touch Calibration (30 samples)
The original approach used synthetic touches, but real behavioral data was needed. The implementation includes:
- A "Minimize App" button that takes users to the home screen
- Instructions to use the phone normally - scroll through apps, open menus, etc.
- AccessibilityService captures these real touches in the background
- Progress tracking shows "Touch samples: 15/30" in real-time
- Users return to the app once they have enough samples

This approach captures genuine user behavior patterns rather than artificial touch generation.

### Phase 2: Typing Calibration (100 characters)
Initially, there was just an "Open Text Field" button that didn't work. This was replaced with:
- An inline text field right in the calibration screen
- Provided sentences to type naturally (no need to think about what to write)
- Real-time character counting: "Typed: 67/100 characters"
- Automatic progression to next phase when complete

The key insight was that users needed to type naturally, not try to "game" the system.

### Phase 3: Baseline Creation (Automatic)
After both touch and typing data is collected:
- Models train automatically in the background
- Tier-0 statistical baselines get established
- Tier-1 autoencoders prepare for behavioral analysis
- User sees "Baseline Ready" when complete

### Phase 4: Test Mode (Manual)
- "Start Test" button begins real monitoring
- Risk meter updates every 3 seconds
- Users can hand the phone to someone else to see risk increase
- "End Test" button stops the monitoring when done

## Fixing the Risk Calculation Issues

The risk calculation was completely broken after calibration. Here's what was discovered and fixed:

### Problem 1: Tier-0 Statistics Going Crazy
The Mahalanobis distance calculation was returning values like 99.99% risk constantly. Investigation revealed:
- The code was taking the maximum distance across all features (way too aggressive)
- Changed to average distance which is much more reasonable
- Reduced the baseline requirement from 50 to 25 samples for faster calibration

### Problem 2: Fusion Weights Were Incorrect
The system was heavily weighting the broken Tier-0 calculations:
- Original: 70% Tier-0, 30% Tier-1
- Fixed: 20% Tier-0, 80% Tier-1
This made a huge difference - now the deep learning analysis has more influence.

### Problem 3: Biometric Success Didn't Reset Risk
After successful authentication, risk would stay high and users would get prompted again immediately. Fixed by:
- Resetting session risk to 0% after biometric success
- Restoring all trust credits
- Clearing consecutive risk counters

## Making Biometric Prompts Actually Work

The biometric authentication had several critical issues:

### UI Problems
- Prompts weren't full-screen (appeared as small notifications)
- Added proper Dialog properties and semi-transparent background
- Now appears as a proper security overlay

### Logic Problems  
- Risk-based messaging now explains WHY verification is needed
- Success properly resets the security state
- Failure keeps the prompt visible for retry

## Debug Logging Implementation

Extensive logging was added throughout the system to understand what was happening. Now the logs show:
- Every step of the risk calculation with actual numbers
- Policy decisions with reasoning ("Escalating because risk 87% > threshold 85%")
- Biometric flow with before/after states
- Fusion algorithm showing how Tier-0, Tier-1, and Chaquopy combine

This makes debugging much easier and will help future development work.

## The Compilation Error Fix

A significant amount of time was spent on a scope issue. The "End Test" button was trying to modify a variable that was shadowed by a function parameter. Fixed by:
- Adding proper callback functions instead of direct state modification  
- Following the existing pattern used by other buttons
- Simple bugs can be the most time-consuming to track down

## Current App Capabilities

### For Demo/Testing
1. **Complete Calibration**: Touch → Type → Baseline → Test flow works end-to-end
2. **Real Behavioral Data**: Captures genuine user patterns, not synthetic data
3. **Visual Feedback**: Progress bars, character counts, clear instructions
4. **Test Mode**: Can hand phone to others and watch risk increase in real-time

### For Production Use
1. **Accurate Risk Calculation**: No more crazy 90% spikes after calibration
2. **Smart Escalation**: Biometric prompts trigger at appropriate thresholds
3. **Proper Session Management**: Risk resets after successful authentication  
4. **Background Monitoring**: Continuous analysis via AccessibilityService

## Technical Improvements Made

### Risk Pipeline Overhaul
- Fixed Mahalanobis distance normalization in Tier0StatsAgent
- Rebalanced fusion weights in FusionAgent (favor Tier-1 over Tier-0)
- Enhanced Chaquopy integration with confidence-based weighting
- Added session risk reset in PolicyAgent biometric success handling

### UI/UX Enhancements  
- Implemented inline typing calibration in MainScreen with real-time tracking
- Enhanced biometric prompts in BiometricPromptSheet with proper overlays
- Added comprehensive state management in SecurityViewModel
- Fixed scope conflicts and callback patterns

### System Reliability
- Added extensive logging in SecurityManager for risk calculation visibility
- Enhanced error handling and state recovery throughout
- Improved calibration phase management and transitions
- Better resource cleanup and lifecycle management

## Performance & User Experience

The app now feels responsive and professional:
- Calibration takes 2-3 minutes instead of being frustratingly broken
- Risk calculations are accurate and don't spike unrealistically  
- Biometric prompts appear reliably and look polished
- Users get clear feedback on what's happening at each step

## Areas for Future Improvement

Several areas for future development were identified:
- The 30 touch / 100 character requirements could be made adaptive
- More sophisticated baseline validation could be added
- The UI could benefit from more detailed progress indicators
- Production deployment would need additional error handling

The app has evolved from a promising concept with broken implementation to a working behavioral biometrics system that demonstrates the core security concepts effectively.

## Files Modified During Development

The major changes were concentrated in these core files:
- `SecurityManager.kt` - Risk calculation pipeline and calibration management
- `PolicyAgent.kt` - Escalation logic and session reset functionality
- `MainScreen.kt` - Complete calibration flow UI and state management
- `SecurityViewModel.kt` - Calibration orchestration and biometric handling
- `BiometricPromptSheet.kt` - Enhanced authentication UI
- `Tier0StatsAgent.kt` - Fixed statistical calculations
- `FusionAgent.kt` - Rebalanced risk fusion weights

Each change was driven by specific user experience problems or technical issues discovered during testing. The result is a much more robust and usable behavioral biometrics demonstration.

## Risk Calculation Deep Dive

### Original Problems
The risk calculation had three major flaws that made the system unusable:

1. **Mahalanobis Distance Overflow**: The Tier-0 agent was calculating distances using maximum values across features, leading to astronomical risk scores that immediately triggered authentication prompts.

2. **Improper Fusion Weighting**: The system gave 70% weight to the broken statistical calculations and only 30% to the more sophisticated Tier-1 autoencoder analysis.

3. **No Session Reset**: After biometric success, all the risk state remained high, causing immediate re-prompting.

### Solutions Implemented
- **Statistical Normalization**: Changed from max() to average() aggregation in Mahalanobis calculations
- **Rebalanced Fusion**: Now 80% Tier-1, 20% Tier-0 weighting gives more importance to deep learning
- **Proper State Management**: Biometric success resets session risk to 0% and restores trust credits
- **Confidence-Based Integration**: Chaquopy ML results weighted by confidence levels

### Current Risk Pipeline
The risk calculation now follows this flow:
1. Raw sensor data → Tier-0 statistical analysis → normalized risk percentage
2. Feature vectors → Tier-1 autoencoder → reconstruction error → risk percentage  
3. Behavioral patterns → Chaquopy Python ML → confidence-weighted risk
4. All risks fused using adaptive weights → final session risk (0-100%)
5. Policy agent evaluates thresholds and consecutive windows → escalation decision

This multi-layered approach provides much more accurate and stable risk assessment than the original broken implementation.