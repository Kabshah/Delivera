# Delivra

**Schedule WhatsApp messages that send automatically — even when your phone is locked.**

Delivra is a fully on-device WhatsApp scheduler for Android. Pick a contact, pick a date &
time, attach anything you like — Delivra wakes up on schedule, delivers the message through
your own WhatsApp account, and goes back to sleep. No servers, no accounts, no cloud.

---

## Overview

| | |
|---|---|
| **What it does** | Schedules WhatsApp messages (text, voice notes, images, PDFs/docs) and sends them automatically at the chosen moment |
| **How it sends** | Links to your WhatsApp as a *companion device* (same protocol as WhatsApp Web) and delivers through your account |
| **Where data lives** | Entirely on your phone — schedules, session keys, attachments. There is no backend server |
| **Battery philosophy** | Connect-on-demand: the app runs only for a couple of minutes around each scheduled send, never in the background otherwise |

### Features

- 📅 Exact date + time scheduling with timezone-safe alarms
- 💬 Text, 🎙 voice notes (real WhatsApp PTT), 🖼 inline images, 📎 PDF/Word/document attachments
- 🔁 Automatic retries with backoff, failure notifications, and a "Needs Review" safety state so nothing double-sends silently
- 🌙 Screen-off delivery via exact alarms + battery-optimization exemption (one-tap toggle inside the app)
- 👤 Contact suggestions from your phone book
- 📦 Two slim APKs (~56 MB) covering every phone from 2017 onwards — Android 8.0+

---

## Download & Install

**⬇ [Download for most phones — arm64-v8a](https://github.com/Kabshah/Delivera/releases/latest/download/Delivra-arm64-v8a.apk)**  
Samsung · Xiaomi · OnePlus · Pixel · Redmi · Realme · Vivo · Oppo — every phone sold since ~2017. *Start here.*

**⬇ [Download for older 32-bit phones — armeabi-v7a](https://github.com/Kabshah/Delivera/releases/latest/download/Delivra-armeabi-v7a.apk)**  
Only if the arm64 APK refuses to install.

> Both buttons always fetch the newest release directly — no need to browse GitHub Releases.
> Using an old version? The original v1.0 remains available in [past releases](../../releases).

### Install steps

1. Tap a button above to download the APK
2. Open the downloaded file; allow **"Install from unknown sources"** if asked
3. Open Delivra → enter your WhatsApp number → you'll get an 8-character code
4. In WhatsApp: **Settings → Linked Devices → Link a Device → "Link with phone number instead"** → enter the code
5. Toggle **Reliable delivery** ON (Home screen) so scheduled sends work while the screen is off

---

## ⚠️ Honesty Notes

Please read before installing — we'd rather over-explain than over-promise:

- **Delivery is near-scheduled, not second-perfect.** Messages normally go out within a few
  minutes of the target time. OEM power management (especially Samsung) can occasionally delay wakeups.
- **The Reliable delivery toggle matters.** Android blocks network access in deep sleep;
  without the battery-optimization exemption, screen-off sends will fail. The app asks once — allowing it costs no extra battery because Delivra still only wakes for its own sends.
- **WhatsApp linked sessions can expire** after long periods without any WhatsApp activity
  (historically ~14 days). Normal daily use avoids this.
- **Attachments must stay reachable.** Files are read at send time — deleting or moving an
  attachment before its schedule causes a clear "source file unavailable" failure, not a silent drop.
- **Crash mid-send = "Needs Review".** If the app dies during a send, the outcome is ambiguous,
  so the message lands in Needs Review for a manual check instead of risking a duplicate.
- **RECEIVE_BOOT_COMPLETED** exists solely to re-register alarms after a reboot — nothing else.
- **The 15-minute WorkManager interval** is an OS minimum, not a design choice.

---

## Permissions Explained

| Permission | Why it's needed |
|---|---|
| `INTERNET` | Speak the WhatsApp Web multi-device protocol |
| `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM` | Fire sends at the precise minute you picked |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Lets the app ask (once) to be exempt from Doze so screen-off sends reach the network |
| `FOREGROUND_SERVICE` (+ `connectedDevice` type) | Runs briefly around each send; shows a small notification while working |
| `RECEIVE_BOOT_COMPLETED` | Re-register all pending schedules after reboot — nothing more |
| `WAKE_LOCK` | Keep the CPU awake for the few minutes a dispatch takes |
| `POST_NOTIFICATIONS` | Failure alerts and the temporary "sending…" notification |
| `READ_CONTACTS` | Name/photo suggestions while composing a message |
| `RECORD_AUDIO` | In-app voice-note recording |
| `READ_EXTERNAL_STORAGE` (≤ Android 12) | Legacy read path for attachments picked via SAF |

---

## Architecture

```
┌───────────────────────────  Your phone  ───────────────────────────┐
│                                                                    │
│  Compose UI ──► Room DB ──► AlarmManager (exact)                   │
│      ▲                            │  due time                      │
│      │                            ▼                                │
│  ViewModels         WorkManager backstop (OS minimum: 15 min)      │
│                                 │                                  │
│                                 ▼                                  │
│                    SchedulerService (foreground)                   │
│                          │            ▲                            │
│                    starts Node.js localhost TCP :3000              │
│                          ▼            │  (JSON lines bridge)       │
│              Node.js runtime ─────────┘                            │
│              └─ Baileys engine ──► WhatsApp (multi-device TLS)     │
└────────────────────────────────────────────────────────────────────┘
```

- **Kotlin + Jetpack Compose** — UI, Room database, alarm/backstop scheduling, retry state machine
- **Embedded Node.js (nodejs-mobile)** — hosts the Baileys WhatsApp engine inside the same app process, started only when there is something to send
- **Local TCP bridge** — typed JSON request/response channel between Kotlin and Node, correlation-id matched
- **Fully offline-first** — no shared backend, no analytics, no telemetry; the only network traffic is with WhatsApp itself

---

*Delivra is an independent utility and is not affiliated with WhatsApp or Meta.*
