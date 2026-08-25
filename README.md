<div align="center">
  <img src=".github/assets/banner.svg" alt="Delivra - schedule WhatsApp messages that send automatically" width="100%"/>
</div>

# Delivra

**Schedule WhatsApp messages that send automatically - even when your phone is locked.**

Fully on-device. Pick a contact, pick a date & time, attach anything you like - Delivra wakes
up on schedule, delivers the message through your own WhatsApp account, and goes back to sleep.
No servers. No accounts. No cloud.

## Get Delivra

<div align="center">

**For most phones** - Samsung / Xiaomi / OnePlus / Pixel / Redmi / Realme / Vivo / Oppo
*(every device sold since ~2017)*

<a href="https://github.com/Kabshah/Delivera/releases/latest/download/Delivra-arm64-v8a.apk">
  <img src=".github/assets/btn-arm64.svg" alt="Download for arm64-v8a" height="76"/>
</a>

<br><br><br>

**Only for older 32-bit-only phones** *(rarely needed)*

<a href="https://github.com/Kabshah/Delivera/releases/latest/download/Delivra-armeabi-v7a.apk">
  <img src=".github/assets/btn-v7a.svg" alt="Download for armeabi-v7a" height="60"/>
</a>

<sub>Both buttons always download the newest release automatically.</sub>

</div>

---

## What Delivra does

| | |
|---|---|
| **What** | Schedules WhatsApp messages (text, voice notes, images, PDFs/docs) and delivers them automatically at the exact moment you choose |
| **How** | Links to your WhatsApp as a *companion device* - same protocol as WhatsApp Web - and sends through your own account |
| **Where your data lives** | Entirely on your phone: schedules, session keys, attachments. No backend server, no analytics, no telemetry |
| **Battery philosophy** | Connect-on-demand: the app runs only ~2 minutes around each scheduled send. The rest of the time it does not exist |

### Feature highlights

- Exact date + time scheduling, timezone-safe by design
- Text, real PTT voice notes, inline photos, PDF / Word / any document
- Screen-off delivery - exact alarms + battery-optimization exemption behind a one-tap in-app toggle
- Smart retry ladder with backoff, failure notifications, and a "Needs Review" state so nothing double-sends silently
- Contact suggestions straight from your phone book
- Slim per-CPU APKs (~56 MB) - Android 8.0+, every phone from 2017 onwards

---

## Getting started

1. Tap **DOWNLOAD ARM64-V8A** above (green button only if your phone is ancient)
2. Open the downloaded file, allow **Install from unknown sources** if asked
3. Open Delivra, enter your WhatsApp number, receive an 8-character code
4. In WhatsApp: **Settings > Linked Devices > Link a Device > "Link with phone number instead"**, type the code
5. Flip the **Reliable delivery** toggle on the Home screen - this makes scheduled sends survive a locked screen

---

## Honesty Notes

We would rather over-explain than over-promise:

- **Delivery is near-scheduled, not second-perfect.** Messages normally go out within minutes of the target time; aggressive OEM power management (especially Samsung) can occasionally delay wakeups.
- **The Reliable delivery toggle matters.** Android cuts network access in deep sleep - without the exemption, screen-off sends fail. Allowing it costs no extra battery: Delivra still wakes only for its own sends.
- **Linked sessions can expire** after long periods without any WhatsApp activity (historically ~14 days).
- **Attachments must stay reachable.** Files are read at send time; deleting or moving one before its schedule produces a clear "source file unavailable" failure instead of a silent drop.
- **Crash mid-send means "Needs Review".** An ambiguous outcome is surfaced for manual checking rather than risking an unnoticed duplicate.
- **RECEIVE_BOOT_COMPLETED** exists purely to restore alarms after reboot.
- **The 15-minute WorkManager interval** is an Android OS floor, not our choice.

---

## Permissions Explained

| Permission | Why |
|---|---|
| `INTERNET` | Speak the WhatsApp multi-device protocol |
| `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM` | Fire sends at the minute you picked |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | One-time ask to bypass Doze so locked-screen sends reach the network |
| `FOREGROUND_SERVICE` (`connectedDevice`) | Brief foreground session around each send, with a visible notification |
| `RECEIVE_BOOT_COMPLETED` | Restore schedules after reboot - nothing else |
| `WAKE_LOCK` | Hold the CPU for the few minutes a dispatch takes |
| `POST_NOTIFICATIONS` | Failure alerts + temporary sending notification |
| `READ_CONTACTS` | Contact name/photo suggestions while composing |
| `RECORD_AUDIO` | In-app voice-note recording |
| `READ_EXTERNAL_STORAGE` (Android 12 and below) | Legacy attachment read path |

---

## Architecture

- **Kotlin + Jetpack Compose** - UI, Room database, alarm/backstop scheduling, retry state machine
- **Embedded Node.js (nodejs-mobile)** - hosts the Baileys WhatsApp engine inside the app process, started only when there is something to send
- **Local TCP bridge** - correlation-id matched JSON channel between Kotlin and Node
- **Offline-first** - the only network traffic is with WhatsApp itself

---

> Looking for the **old version**? The original v1.0 build is preserved separately in [past releases](../../releases) and on the [`v1` branch](../../tree/v1).

*Delivra is an independent utility and is not affiliated with WhatsApp or Meta.*