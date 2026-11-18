# MYRA – Gemini Live Voice Assistant (Android)

Kotlin app: Gemini Live WebSocket voice, orb UI, Hinglish commands (call / SMS / WhatsApp /
open apps / volume / torch), incoming-call announce, floating overlay, accessibility helper.

## Termux se build
```
pkg update && pkg install openjdk-17 gradle aapt2 unzip
termux-setup-storage
unzip MYRA.zip && cd MYRA
./build.sh
```
- Android SDK chahiye: `platforms;android-34` + `build-tools;34.0.0` (ARM/Termux-compatible build).
  `~/android-sdk` me rakho ya `ANDROID_HOME` set karo (build.sh `local.properties` bana deta hai).
- `aapt2` ke liye build.sh automatically Termux ka `aapt2` use karta hai.
- Output: `app/build/outputs/apk/debug/app-debug.apk` (+ copy `Download/MYRA.apk`).

### Agar Termux me SDK ka jhanjhat ho
Project ko GitHub repo me push karo – `.github/workflows/build.yml` APK khud build karke
Actions → Artifacts me de dega.

## Pehli baar chalane ke baad
1. Settings (⚙️) → Gemini API key, naam, voice, personality, prime contacts.
2. Permissions allow karo + "Draw over other apps" ON.
3. Accessibility → MYRA ON (open/close app, WhatsApp send/call ke liye).
4. Double power-press → floating orb.

## Notes / deviations
- Foreground services `specialUse` type use karte hain (Android 14 me phoneCall/microphone type crash karta).
- Power-button receiver manifest me kaam nahi karta (Android implicit broadcast), isliye
  `CallMonitorService` me dynamically register hai.
- WiFi/Bluetooth Android 10+ me silently toggle nahi ho sakte → system panel khulta hai.
- WhatsApp message/call "Send"/"Voice call" button Accessibility se click hota hai.
- `GeminiLiveClient.USE_LEGACY_MEDIA_CHUNKS` = false karo agar server `media_chunks` reject kare.
- AGP 8.7.3 / Gradle 8.9 / Kotlin 1.9.24 / minSdk 26 / targetSdk 34.
