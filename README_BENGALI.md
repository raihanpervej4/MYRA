# MYRA Android — Termux build project

এই ZIP-এ Kotlin Android Studio project আছে। এটি আপনার `MYRA_MASTER_PROMPT.md`-এর ভিত্তিতে তৈরি একটি **প্রথম কার্যকর MVP**: dark UI, Settings, Gemini Live WebSocket transport, PCM microphone/speaker pipeline, text chat, mute এবং long-press stop।

## Termux থেকে APK build

Android SDK/Gradle/JDK ইনস্টল ও configure করা থাকতে হবে। শুধু Termux-এ `gradle` দিলেই সাধারণত Android SDK পাওয়া যায় না।

1. ZIP-টি extract করুন।
2. project folder-এ যান:
   `cd MYRA_Android`
3. `local.properties` ফাইলে আপনার Android SDK path দিন, উদাহরণ:
   `sdk.dir=/data/data/com.termux/files/home/android-sdk`
4. JDK 17 ও Android SDK Platform 34 + Build Tools ইনস্টল থাকতে হবে।
5. Build:
   `gradle :app:assembleDebug`
6. APK:
   `app/build/outputs/apk/debug/app-debug.apk`

যদি আপনার Termux-এ Android SDK/Gradle setup না থাকে, Android Studio-তে project খুলে Build > Build APK(s) ব্যবহার করুন।

## প্রথমবার চালু
- Settings খুলে Gemini API key দিন, নাম/voice/model বেছে Save করুন।
- অ্যাপ restart করুন এবং microphone permission দিন।
- API key গোপন রাখুন; APK-তে embedded নয়, app preferences-এ রাখা হয়।

## গুরুত্বপূর্ণ সীমাবদ্ধতা
এই সংস্করণটি সম্পূর্ণ master prompt-এর সব ফিচার নয়। Prime contacts, phone/SMS/WhatsApp automation, incoming-call monitor, accessibility actions, floating overlay, power-button trigger, boot receiver, waveform/orb animation এবং reconnect/session renewal এখনো যুক্ত করা হয়নি। এগুলো Android permission, OS version ও Play policy-র কারণে আলাদা করে নিরাপদভাবে implement/test করতে হবে।

Gemini Live API-র model নাম ও WebSocket protocol সময়ের সঙ্গে বদলাতে পারে। আপনার prompt-এ থাকা model IDs রাখা হয়েছে; Google AI Studio/API documentation-এ বর্তমান model access যাচাই করুন। API key বা model access ভুল হলে live voice সংযোগ হবে না। এই project এখানে Android device/emulator-এ build বা runtime-test করা হয়নি।
