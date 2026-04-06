# Gemma 4 auf Pixel 9 - Installationsanleitung

## Methode 1: Cross-Build (Empfohlen - Einfacher)
Auf PC/Mac bauen, auf Pixel 9 installieren.

### Auf dem PC/Mac:
```bash
# Repository klonen
git clone https://github.com/parttimenerd/local-android-ai.git
cd local-android-ai

# Android SDK Pfad setzen (anpassen!)
echo "sdk.dir=$HOME/Android/Sdk" > local.properties

# Build
./gradlew assembleDebug

# Auf Pixel 9 installieren (USB-Debugging aktivieren!)
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Auf dem Pixel 9:
1. App öffnen
2. Hugging Face Token eingeben
3. Gemma 4 herunterladen
4. Server starten

---

## Methode 2: Direkt auf Pixel 9 bauen (Komplexer)

### Schritt 1: Termux einrichten
```bash
# Pakete aktualisieren
pkg update && pkg upgrade -y

# Java & Git installieren
pkg install -y openjdk-17 git
```

### Schritt 2: Android SDK manuell installieren
```bash
# Verzeichnis erstellen
mkdir -p $PREFIX/opt/android-sdk
cd $PREFIX/opt/android-sdk

# Command Line Tools herunterladen
wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip

# Entpacken
pkg install -y unzip
unzip commandlinetools-linux-11076708_latest.zip
mkdir -p cmdline-tools/latest
mv cmdline-tools/* cmdline-tools/latest/ 2>/dev/null || true

# Umgebungsvariablen setzen
echo 'export ANDROID_SDK_ROOT=$PREFIX/opt/android-sdk' >> ~/.bashrc
echo 'export PATH=$PATH:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin' >> ~/.bashrc
source ~/.bashrc
```

### Schritt 3: SDK Komponenten installieren
```bash
# Lizenz akzeptieren
yes | sdkmanager --licenses

# Platform & Build Tools installieren
sdkmanager "platforms;android-34"
sdkmanager "build-tools;34.0.0"
sdkmanager "platform-tools"
```

### Schritt 4: Projekt klonen & bauen
```bash
cd ~
git clone https://github.com/parttimenerd/local-android-ai.git
cd local-android-ai

echo "sdk.dir=$PREFIX/opt/android-sdk" > local.properties
chmod +x gradlew
./gradlew assembleDebug
```

### Schritt 5: Installieren
```bash
# APK kopieren
cp app/build/outputs/apk/debug/app-debug.apk /sdcard/Download/

# Installation starten
termux-open app/build/outputs/apk/debug/app-debug.apk
```

---

## Methode 3: GitHub Actions Build (Am einfachsten)

1. Fork das Repository auf GitHub
2. Gehe zu **Actions** → **Build APK** → **Run workflow**
3. Lade die APK aus den Artifacts herunter
4. Übertrage auf Pixel 9 (USB, Cloud, etc.)
5. Installiere die APK

---

## Gemma 4 Einrichten (Alle Methoden)

1. **App öffnen**
2. **Berechtigungen erteilen** (Speicher, ggf. Kamera)
3. **AI Models** → Hugging Face Token eingeben
   - Auf [huggingface.co](https://huggingface.co) einloggen
   - Settings → Access Tokens → New Token
4. **Gemma 4 E4B IT** auswählen
5. **Download** starten (~2.96 GB)
6. **Server starten**

---

## Testen

```bash
# IP-Adresse finden
ip addr show wlan0 | grep "inet "

# Server testen
curl http://localhost:8005/status

# AI testen
curl -X POST http://localhost:8005/ai/text \
  -H "Content-Type: application/json" \
  -d '{"model": "GEMMA_4_E4B_IT", "prompt": "Hallo!"}'
```

---

## Fehlerbehebung

### "Out of Memory" beim Build
```bash
export GRADLE_OPTS="-Xmx2g -XX:MaxMetaspaceSize=512m"
```

### "SDK not found"
```bash
# Prüfe SDK-Pfad
echo $ANDROID_SDK_ROOT
ls -la $ANDROID_SDK_ROOT/platforms/
```

### Installation blockiert
- Einstellungen → Apps → Spezieller App-Zugriff → Unbekannte Apps installieren → Termux erlauben
