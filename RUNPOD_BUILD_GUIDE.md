# Gemma 4 auf Pixel 9 - Komplette RunPod Build-Anleitung

Diese Anleitung beschreibt den kompletten Workflow:
1. Repository forken
2. Gemma 4 Support hinzufügen
3. In RunPod bauen
4. APK auf Pixel 9 installieren

---

## Teil 1: Repository vorbereiten (Lokal)

### Schritt 1.1: Fork auf GitHub erstellen

1. Öffne: https://github.com/parttimenerd/local-android-ai
2. Klicke oben rechts auf **"Fork"** 🍴
3. Wähle deinen GitHub-Account
4. Warte bis der Fork erstellt ist
5. Dein Fork ist jetzt unter `https://github.com/DEIN_GITHUB_USERNAME/local-android-ai` erreichbar

### Schritt 1.2: Repository klonen (falls noch nicht geschehen)

```bash
# Ins Home-Verzeichnis
cd ~

# Original Repository klonen (oder deinen Fork)
git clone https://github.com/parttimenerd/local-android-ai.git
cd local-android-ai
```

### Schritt 1.3: Gemma 4 Support hinzufügen

Falls noch nicht vorhanden, erstelle die Datei `app/src/main/kotlin/me/bechberger/phoneserver/ai/AIModel.kt` mit folgendem Eintrag:

```kotlin
GEMMA_4_E4B_IT(
    modelName = "Gemma 4 E4B IT",
    fileName = "gemma-4-E4B-it-web.task",
    url = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it-web.task",
    licenseUrl = "https://ai.google.dev/gemma/terms",
    preferredBackend = Backend.GPU,
    thinking = false,
    temperature = 1.0f,
    topK = 64,
    topP = 0.95f,
    supportsVision = true,
    maxTokens = 4096,
    description = "Gemma 4 4B instruction-tuned model with vision support, optimized for on-device inference",
    needsAuth = true,
    licenseStatement = "This response was generated using Gemma 4, a model developed by Google. Usage is subject to the Gemma Terms of Use: https://ai.google.dev/gemma/terms"
);
```

### Schritt 1.4: Änderungen committen und zu deinem Fork pushen

```bash
# In das Repository-Verzeichnis wechseln
cd ~/local-android-ai

# Deinen Fork als Remote hinzufügen (ersetze DEIN_GITHUB_USERNAME!)
git remote add fork https://github.com/DEIN_GITHUB_USERNAME/local-android-ai.git

# Änderungen stagen
git add -A

# Commit erstellen
git commit -m "Add Gemma 4 E4B IT support with GPU acceleration for Pixel 9"

# Zu deinem Fork pushen
git push fork main

# Erfolg prüfen
echo "✅ Dein Fork ist jetzt unter:"
echo "https://github.com/DEIN_GITHUB_USERNAME/local-android-ai"
```

---

## Teil 2: In RunPod bauen (Cloud)

### Schritt 2.1: RunPod Pod erstellen

1. Gehe zu: https://www.runpod.io/
2. Einloggen/Registrieren
3. **"GPU Cloud"** → **"Deploy"**
4. Template wählen:
   - **PyTorch** oder **Ubuntu**
   - GPU: RTX 3090 oder besser (für schnelleren Build)
5. **"Deploy"** klicken
6. Warte bis der Pod **"Running"** ist

### Schritt 2.2: RunPod Terminal öffnen

1. Klicke auf deinen Pod
2. **"Connect"** → **"Start"** (für Jupyter)
3. Oder **"Connect"** → **"Terminal"** für direkten SSH-Zugang

### Schritt 2.3: Android SDK installieren

```bash
# Arbeitsverzeichnis
cd /workspace

# Android SDK Verzeichnis erstellen
export ANDROID_SDK_ROOT=/workspace/android-sdk
mkdir -p $ANDROID_SDK_ROOT

# Command Line Tools herunterladen
wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip

# Entpacken
apt-get update && apt-get install -y unzip
unzip -q commandlinetools-linux-11076708_latest.zip -d $ANDROID_SDK_ROOT/

# Richtige Struktur erstellen
mkdir -p $ANDROID_SDK_ROOT/cmdline-tools/latest
mv $ANDROID_SDK_ROOT/cmdline-tools/bin $ANDROID_SDK_ROOT/cmdline-tools/latest/
mv $ANDROID_SDK_ROOT/cmdline-tools/lib $ANDROID_SDK_ROOT/cmdline-tools/latest/

# Umgebungsvariablen setzen
export PATH=$PATH:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin

# Lizenz akzeptieren
yes | sdkmanager --licenses

# Benötigte SDK-Komponenten installieren
sdkmanager "platforms;android-34"
sdkmanager "build-tools;34.0.0"
sdkmanager "platform-tools"
sdkmanager "ndk;26.1.10909125"

echo "✅ Android SDK installiert unter: $ANDROID_SDK_ROOT"
```

### Schritt 2.4: Repository klonen und bauen

```bash
# In Arbeitsverzeichnis
cd /workspace

# Deinen Fork klonen (ersetze DEIN_GITHUB_USERNAME!)
git clone https://github.com/DEIN_GITHUB_USERNAME/local-android-ai.git
cd local-android-ai

# local.properties erstellen
echo "sdk.dir=/workspace/android-sdk" > local.properties

# Gradle Wrapper ausführbar machen
chmod +x gradlew

# Build starten (dauert 5-10 Minuten)
./gradlew assembleDebug

# Erfolg prüfen
ls -lh app/build/outputs/apk/debug/
```

### Schritt 2.5: APK zum Download bereitstellen

```bash
# In das APK-Verzeichnis wechseln
cd /workspace/local-android-ai/app/build/outputs/apk/debug/

# Einfacher HTTP-Server starten
python3 -m http.server 8888 &

# Oder: Kopiere APK in RunPod's öffentlichen Bereich
cp app-debug.apk /workspace/

echo "✅ APK bereit unter:"
echo "http://$(hostname -I | awk '{print $1}'):8888/app-debug.apk"
```

Alternativ über RunPod's Download-Feature:
```bash
# Die APK wird automatisch in RunPod's Datei-Browser angezeigt
# Gehe zu: RunPod UI → Pod → Files → /workspace/local-android-ai/app/build/outputs/apk/debug/
```

---

## Teil 3: APK auf Pixel 9 installieren

### Methode A: Direkter Download auf Pixel 9

```bash
# Auf deinem Pixel 9 (Termux)
cd /sdcard/Download

# APK von RunPod herunterladen (ersetze RUNPOD_IP!)
wget http://RUNPOD_IP:8888/app-debug.apk

# Installation starten
termux-open app-debug.apk
```

### Methode B: Über PC/Cloud

1. Lade APK von RunPod herunter (über Browser oder `scp`)
2. Übertrage auf Pixel 9 via:
   - Google Drive
   - USB-Kabel
   - ADB

### Methode C: ADB (empfohlen)

```bash
# Auf deinem PC
adb install -r app-debug.apk
```

---

## Teil 4: Gemma 4 einrichten

### Schritt 4.1: App starten

1. Öffne **"Local AI Phone Server"** auf dem Pixel 9
2. Erteile alle Berechtigungen (Speicher, Kamera optional)

### Schritt 4.2: Hugging Face Token erstellen

1. Gehe zu: https://huggingface.co/settings/tokens
2. **"New Token"** klicken
3. Name: `pixel9-gemma4`
4. Role: `read`
5. **"Generate"**
6. Token kopieren

### Schritt 4.3: Modell herunterladen

1. In der App: **"AI Models"** öffnen
2. Hugging Face Token eingeben
3. **"Gemma 4 E4B IT"** aus der Liste wählen
4. Auf **"Download"** tippen (~2.96 GB)
5. Warte bis der Download fertig ist

### Schritt 4.4: Server starten

1. Zurück zur Hauptseite
2. **"Start Server"** tippen
3. Server läuft jetzt auf Port **8005**

---

## Teil 5: Testen

### Auf dem Pixel 9 (Termux):

```bash
# Server-Status prüfen
curl http://localhost:8005/status

# Verfügbare Modelle anzeigen
curl http://localhost:8005/ai/models

# Textgenerierung testen
curl -X POST http://localhost:8005/ai/text \
  -H "Content-Type: application/json" \
  -d '{
    "model": "GEMMA_4_E4B_IT",
    "prompt": "Erkläre mir den Tensor G4 im Pixel 9",
    "maxTokens": 200
  }'
```

### Von einem anderen Gerät im gleichen WLAN:

```bash
# IP des Pixel 9 herausfinden
# (Auf Pixel 9 in Termux: ip addr show wlan0)

# Test vom PC/anderem Gerät
curl http://PIXEL9_IP:8005/status

curl -X POST http://PIXEL9_IP:8005/ai/text \
  -H "Content-Type: application/json" \
  -d '{
    "model": "GEMMA_4_E4B_IT",
    "prompt": "Was kannst du alles?"
  }'
```

---

## Zusammenfassung: Alle Befehle auf einen Blick

### Lokal (einmalig):
```bash
cd ~/local-android-ai
git remote add fork https://github.com/DEIN_GITHUB_USERNAME/local-android-ai.git
git add -A
git commit -m "Add Gemma 4 support"
git push fork main
```

### In RunPod:
```bash
cd /workspace && \
export ANDROID_SDK_ROOT=/workspace/android-sdk && \
mkdir -p $ANDROID_SDK_ROOT && \
wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip && \
unzip -q commandlinetools-linux-11076708_latest.zip -d $ANDROID_SDK_ROOT/ && \
mkdir -p $ANDROID_SDK_ROOT/cmdline-tools/latest && \
mv $ANDROID_SDK_ROOT/cmdline-tools/bin $ANDROID_SDK_ROOT/cmdline-tools/latest/ && \
mv $ANDROID_SDK_ROOT/cmdline-tools/lib $ANDROID_SDK_ROOT/cmdline-tools/latest/ && \
export PATH=$PATH:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin && \
yes | sdkmanager --licenses && \
sdkmanager "platforms;android-34" "build-tools;34.0.0" && \
git clone https://github.com/DEIN_GITHUB_USERNAME/local-android-ai.git && \
cd local-android-ai && \
echo "sdk.dir=/workspace/android-sdk" > local.properties && \
chmod +x gradlew && \
./gradlew assembleDebug && \
cp app/build/outputs/apk/debug/app-debug.apk /workspace/
```

### Auf Pixel 9:
```bash
cd /sdcard/Download
wget http://RUNPOD_IP:8888/app-debug.apk
termux-open app-debug.apk
```

---

## Fehlerbehebung

### "SDK not found" in RunPod
```bash
export ANDROID_SDK_ROOT=/workspace/android-sdk
export PATH=$PATH:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin
```

### "Out of Memory" beim Build
```bash
export GRADLE_OPTS="-Xmx8g -XX:MaxMetaspaceSize=512m"
./gradlew assembleDebug --no-daemon
```

### APK Installtion blockiert
- Einstellungen → Apps → Spezieller App-Zugriff → Unbekannte Apps installieren → Erlauben

### Gemma 4 Download bricht ab
- WLAN-Verbindung prüfen
- 3GB+ freier Speicher
- Hugging Face Token korrekt?

---

## Nächste Schritte

- **Vision/Multimodal**: Kamera-Berechtigung erteilen, dann Bilder + Text senden
- **GPU prüfen**: In App unter AI Models → Test → sollte "GPU" als Backend zeigen
- **Performance**: Bei langsamer Inference prüfen ob GPU genutzt wird

---

**Fragen?** Prüfe die Logs in der App oder über `adb logcat`.
