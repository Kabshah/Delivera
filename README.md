# Delivra

**Schedule WhatsApp messages to send automatically even if your screen is off.**

Send text, voice notes, and PDF/Word attachments to any WhatsApp contact at a specific date + time. No shared backend — everything runs on your own phone.

> Distributed via [GitHub Releases](../../releases), not the Play Store.  
> WhatsApp automation violates Play Store policy, so you install the APK directly from this repo.

---

## ⚠️ Honesty Notes (please read before installing)

- **Delivery is best-effort, not guaranteed exact-second.** The app aims to send within a few minutes of the scheduled time. OEM battery management (especially Samsung) can delay or drop wakeups even with everything configured correctly.
- **WhatsApp linked sessions can expire** if your primary phone's WhatsApp goes completely offline for an extended period (historically ~14 days of no WhatsApp use). Normal daily use avoids this.
- **`RECEIVE_BOOT_COMPLETED` permission** — the app uses this exclusively to reschedule alarms after your phone reboots. Without it, messages scheduled before a reboot wouldn't fire after restart.
- **Attachments are read from their original location** — if you delete or move the file before the scheduled send time, that message will fail with a "source file unavailable" status rather than silently vanishing.
- **Ambiguous send outcomes** — if the app crashes mid-send, the message may show as "Needs Review" rather than Sent or Failed. Check WhatsApp manually in this case to avoid duplicate sends.
- **The 15-minute WorkManager interval** is an Android OS minimum, not a design flaw.

---

## Installation

### Requirements
- Android 8.0+ (API 26+)
- WhatsApp installed on the same phone

### Steps
1. Download `app-release-unsigned.apk` from [Releases](../../releases)
2. Enable **Install from unknown sources** for your file manager or browser app
3. Open the APK and install
4. On first launch, follow the WhatsApp linking flow (pairing code)

### ADB install (alternative)
```bash
adb install app-release-unsigned.apk
```

---

## First-time setup

1. **Link WhatsApp**: open Delivra → enter your WhatsApp number → get an 8-character pairing code → on your WhatsApp app go to **Settings → Linked Devices → Link a Device → "Link with phone number instead"** → enter the code.
2. **Grant permissions when prompted**:
   - Exact alarm scheduling (Android 12+ only)
   - Battery optimization exemption (strongly recommended for reliable delivery)
3. If you're on **Samsung**, you may also need to add Delivra to your autostart list in Device Care settings (the app shows a prompt with instructions).

---

## Build from source

Want to build Delivra yourself instead of downloading a release? Here's how.

### Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | 17 | Temurin recommended; must be on your `PATH` or set as `JAVA_HOME` |
| Android SDK | API 35 | Via [Android Studio](https://developer.android.com/studio) or `sdkmanager` |
| Android NDK + CMake | Latest LTS | Required — the project compiles a native bridge (`CMakeLists.txt`) |
| Node.js | 20+ | Only used once, to fetch the WhatsApp engine's npm dependencies |

### 1. Clone the repo

```bash
git clone https://github.com/<your-username>/Delivra.git
cd Delivra/delivra
```

> **Note:** `local.properties` is gitignored. If you don't have one, point the build at your SDK either by creating it manually:
>
> ```properties
> sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk   # Windows example
> ```
>
> ...or by setting the `ANDROID_HOME` environment variable. Opening the project in Android Studio once does this automatically.

### 2. Install Node.js dependencies (WhatsApp engine)

The Baileys-based engine lives in `app/src/main/assets/nodejs-project` and gets bundled into the APK as-is:

```bash
cd app/src/main/assets/nodejs-project
npm install --production
cd ../../../../..
```

### 3. Build the APK

**Windows (PowerShell / CMD):**
```powershell
.\gradlew.bat assembleDebug        # debug APK
.\gradlew.bat assembleRelease      # unsigned release APK
```

**Linux / macOS:**
```bash
./gradlew assembleDebug            # debug APK
./gradlew assembleRelease          # unsigned release APK
```

First build takes a while (Gradle + native toolchain downloads). Output lands here:

```
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release-unsigned.apk
```

Then install it:

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Using Docker (reproducible — same as CI)

```bash
docker build -t delivra-build .
docker run --rm -v "$(pwd)":/workspace -w /workspace delivra-build ./gradlew assembleRelease
```

---

## Releases (GitHub Actions)

This repo ships APKs automatically — no manual building required for end users:

- **Every push / PR to `main`** → workflow `.github/workflows/build-apk.yml` builds debug + release APKs and uploads them as downloadable artifacts on the run page (**Actions → Build Delivra APK → artifacts**).
- **Tagging a release** (`v1.0.0`, etc.) → the same workflow publishes a proper [GitHub Release](../../releases) with both APKs attached:

```bash
git tag v1.0.0
git push origin v1.0.0
```

Release APKs are **unsigned**, so installers need "Install from unknown sources" enabled (or use `adb install`).

---

## Permissions explained

| Permission | Why |
|---|---|
| `RECEIVE_BOOT_COMPLETED` | Reschedule alarms after reboot — nothing else |
| `SCHEDULE_EXACT_ALARM` | Required for exact-time message delivery on Android 12+ |
| `FOREGROUND_SERVICE` | Runs briefly while sending a message, then stops |
| `POST_NOTIFICATIONS` | Shows failure / "Needs Review" alerts |
| `INTERNET` | WhatsApp Web multi-device protocol |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Prompted once during onboarding to improve delivery reliability |

---

## Architecture

- **Kotlin / Jetpack Compose** — UI, Room database, AlarmManager scheduling, WorkManager backstop
- **Node.js (nodejs-mobile-android) + Baileys** — WhatsApp Web multi-device protocol, runs inside a Foreground Service only when actively sending
- **Fully on-device** — no shared backend, no cloud, no analytics