# JARVIS (জার্ভিস) — মোবাইল AI অ্যাসিস্ট্যান্ট (Android App)

আপনার দেওয়া JARVIS HTML ফাইলটাকেই এখন আসল Android অ্যাপ বানানো হয়েছে।
অ্যাপের স্ক্রিন = আপনার JARVIS UI (`app/src/main/assets/jarvis/index.html`),
আর মাইক/স্পিকার/ফোন কন্ট্রোলের কাজ করে নেটিভ Android ব্রিজ (`MainActivity.kt`)।

## 📲 ফোনে ডাউনলোড ও ইনস্টল করুন (সবচেয়ে সহজ)

বিল্ড শেষ হলে (নিচে সবুজ ✔ এলে) এই লিংকে ফোনের ব্রাউজার থেকে ট্যাপ করুন:

**➡️ https://github.com/editorshamim4108-cmd/NEXU/releases/download/jarvis-latest/JARVIS.apk**

1. `JARVIS.apk` ডাউনলোড শেষ হলে ফাইলটিতে ট্যাপ করুন।
2. "Install unknown apps / অজানা অ্যাপ ইনস্টল" অনুমতি চাইলে **Allow** দিন।
3. **Install** চাপুন — হোম স্ক্রিনে **JARVIS** অ্যাপ পেয়ে যাবেন।

বিকল্প: এই রিপোর **Actions** ট্যাব → সর্বশেষ **Build JARVIS Android APK** রান →
**Artifacts** থেকে `JARVIS-Android-APK` ডাউনলোড করলেও একই APK পাবেন।

> ⏳ কোড পুশের পর APK তৈরি হতে ৩–৬ মিনিট লাগতে পারে। Actions-এ সবুজ ✔ না আসা পর্যন্ত লিংকে পুরনো/কোনো ফাইল নাও থাকতে পারে।

## 🎙 প্রথমবার চালালে যা করবেন

1. অ্যাপ খুললে মাইক্রোফোনের অনুমতি চাইবে — **Allow** দিন (না দিলে ভয়েস শুনবে না)।
2. মাঝখানের বড় Orb বাটনে ট্যাপ করে কথা বলুন, অথবা নিচে লিখে পাঠান।
3. স্ক্রিনের লেখা পড়া / ফোন লক জাতীয় কাজের জন্য ফোনের
   **Settings → Accessibility → Installed apps → JARVIS/NEXU → ON** করুন।
4. ⚙ **Settings (গিয়ার বাটন):** নিজের ElevenLabs API Key (থাকলে) এখানে বসান।
   Key না দিলেও ফ্রি ভয়েস (Element 3) চলবে। সেটিংস শুধু আপনার ফোনেই সেভ থাকে।

## ⚠️ নিরাপত্তা নোট (জরুরি)

আপনার পাঠানো HTML ফাইলের ভেতরে **দুটি ElevenLabs API Key হার্ডকোড করা ছিল**।
এই রিপোটি **Public**, তাই সেই Key গুলো অ্যাপ থেকে সরিয়ে দেওয়া হয়েছে —
না হলে GitHub-এ যে কেউ Key দেখে আপনার ক্রেডিট খরচ করতে পারত।
**ElevenLabs-এ গিয়ে ওই দুটি পুরনো Key ডিলিট/Revoke করে নতুন Key নিন,**
তারপর অ্যাপের Settings-এ নতুন Key বসান। নতুন Key কখনো কোডে/GitHub-এ লিখবেন না।

## 🛠 কীভাবে কাজ করে

| অংশ | প্রযুক্তি |
|---|---|
| JARVIS স্ক্রিন (Orb, ওয়েভ, চ্যাট) | আপনার HTML — `app/src/main/assets/jarvis/index.html` |
| কথা শোনা (মাইক) | Android `SpeechRecognizer` → `window.AndroidJarvis` ব্রিজ |
| কথা বলা | ElevenLabs (নিজের Key) → ফ্রি Google ভয়েস → ফোনের TTS (ফলব্যাক) |
| প্রশ্নের উত্তর | Pollinations AI (ইন্টারনেট লাগবে) |
| স্ক্রিন পড়া / ফোন লক | `NexuVisualAccessibilityService` (Accessibility) |
| ফ্ল্যাশলাইট / অ্যাপ খোলা | নেটিভ `CameraManager` / `PackageManager` |
| APK তৈরি | GitHub Actions (`.github/workflows/build-apk.yml`) — পুশ করলেই অটো-বিল্ড ও Release |

মূল NEXU নেটিভ ইঞ্জিনের ফাইলগুলো (`NexuVoiceEngine.kt` ইত্যাদি) আগের মতোই আছে;
লঞ্চার স্ক্রিন এখন JARVIS। আগের জিপটাও (`NEXU_Android_Project_GitHub_Ready.zip`) রেখে দেওয়া হয়েছে।

---

# PROJECT NEXU — PERSONAL HANDS-FREE ANDROID AI ASSISTANT (হেই বস!)

**NEXU** হলো আপনার ব্যক্তিগত হ্যান্ডস-ফ্রি Android AI Voice Assistant। একবার ডাকলে এটি সক্রিয় হয় এবং আপনি নিজে অফ করতে না বলা পর্যন্ত আপনার নির্দেশ পালন করে।

## অন্তর্ভুক্ত মডিউল ও ফিচারসমূহ
- **Personal Identity:** সব সময় আপনাকে **"বস"** বলে সম্বোধন করে।
- **Hey Boss Wake Word & Foreground Service:** ব্যাকগ্রাউন্ডে কাজ করে (`NexuVoiceForegroundService`)।
- **Persistent Assistant Mode:** একের পর এক কমান্ড নেয়, অসমাপ্ত কাজ Resume করে।
- **Live Visual Phone Control:** AccessibilityService দিয়ে অ্যাপ খোলা, স্ক্রল, স্ক্রিন পড়া, ভয়েসে ফোন লক।
- **Two-Hour Rest System:** টানা ২ ঘণ্টা কাজের পর বিশ্রামের রিমাইন্ডার।
- **Futuristic UI:** নিওন গ্লোয়িং AI Orb ও অ্যানিমেটেড সাউন্ড ওয়েভসহ JARVIS ইন্টারফেস।
