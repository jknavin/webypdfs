# WebyPDF – native Android app (Java)

17 offline PDF tools: Merge, Split, Extract, Remove, Organize, Rotate, Crop, Compress, Grayscale,
Images↔PDF, PDF→Text, Text→PDF, Page numbers, Watermark, Sign, Properties, Protect, Unlock.
No internet permission – files never leave the phone.

## Build the APK (easiest: GitHub, no PC needed)
1. Create a GitHub repo, upload ALL files of this folder (keep folder structure; includes `.github/workflows/build-apk.yml`).
2. Open the **Actions** tab → "Build APK" → Run workflow (or it runs on every push).
3. When green, open the run → **Artifacts** → download `WebyPDF-debug-apk` (installable) / release apk.
4. On your phone: allow "install unknown apps", open the APK.

## Android Studio
File → Open this folder → wait for Gradle sync → Build → Build APK(s).

Requirements: JDK 17, Android SDK 34. Min Android 7.0 (API 24).
