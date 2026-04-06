# AI Phone Server

A simple Android app that serves local AI models via an AI with 
MediaPipe AI inference, object detection, and device sensors for easy integration
with an Android terminal.

**This is highly experimental, so use it with care and I'm not an Android Developer, so, 
full disclosure, Google Gemini helped with the UI part.**

## Download

Download it from the [Releases](https://github.com/parttimenerd/local-a ndroid-ai/releases) page.

## Features

### AI Inference
- **LLM Support**: Gemma 4 E4B IT, Gemma 3n E2B IT, DeepSeek-R1 Distill Qwen 1.5B, Llama 3.2 (1B/3B), TinyLlama 1.1B
- **Object Detection**: MediaPipe EfficientDet Lite 2
- **Model Management**: Download, test, performance metrics (tokens/second)
- **Streaming**: Real-time token streaming with cancellation support

### Device Integration
- **Camera**: Front/rear with zoom, base64 encoding
- **Sensors**: Compass orientation (azimuth, pitch, roll)
- **Permissions**: Runtime request handling

## API Endpoints (Port 8005)

- JSON responses with CORS support
- Error handling with HTTP status codes
- Built-in documentation at `/help`

### AI Services
```http
POST /ai/text
{
  "prompt": "text",
  "model": "gemma-3n-e2b-it", 
  "maxTokens": 150,
  "temperature": 0.7,
  "topK": 40,
  "topP": 0.95
}
```

```http
POST /ai/object_detection
{
  "side": "rear|front",
  "threshold": 0.6,
  "maxResults": 5,
  "returnImage": false
}
```

```http
GET /ai/models
POST /ai/models/download {"modelName": "model-name"}
POST /ai/models/test {"modelName": "model-name", "prompt": "text"}
```

### Device & System
```http
GET /orientation    # Compass: azimuth, pitch, roll, accuracy  
GET /capture?side=rear&zoom=2.0  # Camera capture, base64 JPEG ⚠️ App must be visible
GET /status         # Server status, features, permissions
GET /help           # API documentation
```

⚠️ **Camera Privacy Notice**: Camera capture requires the Android app to be visible due to Android OS privacy restrictions. This ensures users are aware when the camera is being accessed, the other endpoints work in the background.

## Build & Install

```bash
# Build APK
./gradlew assembleDebug

# Install via ADB
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Monitor logs
adb logcat -s "LocalAIPhoneServer" "*AI*"
```

## Integration

Designed for K3s cluster nodes running on Android devices. Provides REST API for cluster applications to access AI inference, device sensors, and camera functionality.

```http
GET /health
# Simple health check endpoint
```

```http
GET /help
# Complete API documentation with examples
```

## 🛠️ Build Instructions

### Prerequisites
- **Android Studio** Arctic Fox or newer (or build on-device with Termux)
- **Android SDK** API level 30+ (Android 11+)
- **Device Requirements**: 4GB+ RAM for Gemma 4, 3GB+ for smaller models
- **Permissions**: Camera, Storage

### Build on Pixel 9 (Termux)
```bash
# In Termux on your Pixel 9
pkg install openjdk-17 gradle android-sdk

# Set SDK path
export ANDROID_SDK_ROOT=$PREFIX/share/android-sdk

# Create local.properties
echo "sdk.dir=$ANDROID_SDK_ROOT" > local.properties

# Build
./gradlew assembleDebug

# Install locally
./gradlew installDebug
```

### Cross-Build for Pixel 9
```bash
# On your development machine
export ANDROID_SDK_ROOT=/path/to/android-sdk

# Create local.properties
echo "sdk.dir=$ANDROID_SDK_ROOT" > local.properties

# Build debug APK with GPU support
./gradlew assembleDebug

# Install on Pixel 9 via ADB
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Building the App
```bash
# Clone the repository
git clone <repository-url>
cd android

# Build debug APK
./gradlew assembleDebug

# Install on connected device  
./gradlew installDebug

# Build release APK
./gradlew assembleRelease
```

### Debug Information
The `/status` endpoint provides comprehensive debug information including:
- Current AI model status and memory usage
- Permission status for all features
- Available device memory and requirements
- Feature availability and configuration

## TODO
- Location isn't working

## License
Apache 2.0