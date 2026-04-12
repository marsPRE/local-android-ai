# AI Phone Server — GPU Fork

This is a fork of [parttimenerd/local-android-ai](https://github.com/parttimenerd/local-android-ai) with added **LiteRT-LM GPU acceleration** and **Gemma 4** support, primarily targeting the **Pixel 9 Pro** (Tensor G4 with OpenCL GPU).

> **Original project**: [parttimenerd/local-android-ai](https://github.com/parttimenerd/local-android-ai) by Johannes Bechberger — a simple Android app that serves local AI models via a REST API with MediaPipe inference, object detection, and device sensors.

## What this fork adds

- **LiteRT-LM backend** alongside MediaPipe — enables `.litertlm` model format
- **GPU acceleration** (OpenCL via `LiteRTBackend.GPU()`) with automatic fallback chain: NPU → GPU → CPU
- **Gemma 4 E2B / E4B** models (both via litert-community download and Edge Gallery import)
- **HuggingFace token authentication** for gated models (Gemma, Llama)
- **Performance fix**: `maxNumTokens = 1024` instead of 4096 — reduced KV cache size gives ~9× speedup (0.4 → 3.6 T/s on Pixel 9 Pro)
- **More models**: Llama 3.2 1B/3B, DeepSeek-R1 Distill Qwen 1.5B, TinyLlama 1.1B
- **Edge Gallery model import**: use a model downloaded by Edge Gallery directly in this app

## Download

Download the APK from the [Releases](https://github.com/marsPRE/local-android-ai/releases) page.

## Performance (Pixel 9 Pro)

| Model | Backend | Speed |
|---|---|---|
| Gemma 4 E4B IT | GPU (OpenCL) | ~3.5 T/s |
| Gemma 4 E2B IT | GPU (OpenCL) | ~5–6 T/s |
| DeepSeek-R1 1.5B | CPU | ~1–2 T/s |

## Features

### AI Inference
- **LLM Support**: Gemma 4 E4B/E2B IT, Gemma 3n E2B IT, DeepSeek-R1 Distill Qwen 1.5B, Llama 3.2 (1B/3B), TinyLlama 1.1B
- **Backends**: LiteRT-LM (GPU/NPU/CPU) and MediaPipe (CPU)
- **Vision**: Multimodal input for Gemma 4 and Gemma 3n models
- **Object Detection**: MediaPipe EfficientDet Lite 2
- **Model Management**: Download, test, performance metrics (tokens/second)
- **HuggingFace Auth**: Token support for gated model downloads
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
  "text": "What is the capital of France?",
  "model": "GEMMA_4_E4B_IT",
  "temperature": 0.7,
  "topK": 40,
  "topP": 0.95
}
```

```http
POST /ai/text  (with camera image)
{
  "text": "Describe what you see",
  "model": "GEMMA_4_E4B_IT",
  "captureConfig": { "camera": "rear" }
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
GET  /ai/models
POST /ai/models/download  {"modelName": "GEMMA_4_E4B_IT"}
POST /ai/models/test      {"modelName": "GEMMA_4_E4B_IT", "prompt": "Hello"}
```

### Device & System
```http
GET /orientation    # Compass: azimuth, pitch, roll, accuracy
GET /capture?side=rear&zoom=2.0  # Camera capture, base64 JPEG ⚠️ App must be visible
GET /status         # Server status, features, permissions
GET /help           # Full API documentation
```

⚠️ **Camera Privacy Notice**: Camera capture requires the Android app to be visible (Android OS restriction). All other endpoints work in the background.

## HuggingFace Token Setup

Some models (Gemma, Llama) require a HuggingFace account and accepted license. Set your token in the app under **Model Manager → HuggingFace Token** or via the settings icon.

1. Create a token at [huggingface.co/settings/tokens](https://huggingface.co/settings/tokens)
2. Accept the model license on HuggingFace
3. Enter the token in the app

## Build & Install

```bash
# Clone this fork
git clone https://github.com/marsPRE/local-android-ai.git
cd local-android-ai

# Build APK
./gradlew assembleDebug

# Install via ADB
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Monitor logs
adb logcat | grep -E "AIInference|LiteRT|GPU|Backend"
```

### Requirements
- Android 11+ (API 30+)
- 4 GB+ RAM recommended for Gemma 4 E4B
- Pixel 9 / Tensor G4 for GPU acceleration (other devices fall back to CPU)

## Integration

Designed for use as a local AI backend accessible via REST — e.g. from scripts, K3s cluster nodes, or other apps on the same network.

```bash
curl -X POST http://<phone-ip>:8005/ai/text \
  -H "Content-Type: application/json" \
  -d '{"text": "Hello", "model": "GEMMA_4_E4B_IT"}'
```

## License

Apache 2.0 — same as the original project.
