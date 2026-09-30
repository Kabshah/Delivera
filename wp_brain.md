# Delivra — Project Brain

> Single source of truth for any AI coding agent (Claude Code, Codex, etc.) building this project. Read fully before writing any code. Do not deviate from decisions marked **DECIDED** — they came from explicit back-and-forth with the user, not assumption. This file was revised after a technical review surfaced real reliability gaps (reboot recovery, idempotency, timezone handling, attachment lifetime, session corruption, concurrency, observability, exact-alarm reality) — those are now first-class, not afterthoughts.

---

## 1. What This Project Is

A **native Android app** that schedules WhatsApp messages (text, voice notes, PDF/Word attachments) to send automatically at a future date/time — even with the screen off or the user away from the phone (phone just needs power + internet).

**DECIDED:** Open-source, distributed via **GitHub Releases** (not Play Store — WhatsApp automation violates Play Store policy). GitHub Actions auto-builds the APK on push/tag. No shared backend — every friend who installs this runs a fully independent instance with their own WhatsApp session and local data. Nothing is cloud-hosted; everything lives on-device.

**Reliability promise (realistic wording — use this, not stronger claims):** the app *aims* to send scheduled messages within a few minutes of the scheduled time, and to catch up quickly if the device was briefly off. It does not guarantee exact-second delivery — this depends on Android OS behavior and OEM battery management outside the app's control (see §6.9).

---

## 2. Architecture Decision

### 2.1 Architecture (DECIDED)

**Split responsibilities cleanly:**

- **Kotlin side = source of truth.** Owns all data (Room database — a first-class, fully-supported Android API, no native addon dependency) and all scheduling (`AlarmManager` with `setExactAndAllowWhileIdle` for exact wakeups, `WorkManager` as a periodic backstop).
- **Node.js side = a thin, stateless "WhatsApp send engine."** Runs inside `nodejs-mobile-android`, hosted by the same Foreground Service, but holds **no persistent data of its own**. It exposes a small set of functions over the JS↔Kotlin bridge: `connect()`, `getContacts()`, `sendMessage(payload)`. Kotlin calls these; Node does the WhatsApp/Baileys work and reports success/failure back over the bridge.

This means: if Node/Baileys ever needs debugging or swapping out, the scheduling and data layer (the part that actually has to be correct and reliable) is untouched.

### 2.2 How exact-time delivery actually works (now includes reboot recovery)

1. When a message is scheduled, Kotlin writes a row to Room **and** registers an `AlarmManager` exact alarm for that time (survives app process death — this is OS-level, not app-level).
2. At the alarm's fire time, a `BroadcastReceiver` starts/wakes the Foreground Service, which ensures the Node/Baileys connection is live, then calls `sendMessage()` over the bridge.
3. **Backstop:** a `WorkManager` periodic job (Android's minimum interval is 15 minutes — this is a hard OS limit, not a design choice) runs every ~15 min and checks Room for any `PENDING` message whose `scheduled_time` has already passed but never got processed. See §6.4 for the missed-schedule policy.
4. The **same** WorkManager job also runs the storage cleanup (§4.8) and the reconciliation pass (§6.7).
5. **Reboot recovery (DECIDED — required, not optional):** `AlarmManager` exact alarms are cleared by Android on every device reboot — this is an OS guarantee, not a bug, and the app must plan around it explicitly.
   - A `BOOT_COMPLETED` `BroadcastReceiver` (`BootReceiver.kt`) runs on every device boot.
   - It queries Room for all `PENDING` rows and re-registers their `AlarmManager` exact alarms.
   - It also runs the reconciliation pass (§6.7) immediately, since a reboot is one of the most likely times to find rows stuck mid-send from before the restart.
   - Any `PENDING` row now overdue is handled per the same missed-schedule grace policy as §6.4 (not sent blindly late).
   - Requires the `RECEIVE_BOOT_COMPLETED` permission, declared plainly in the manifest and disclosed in the README (§7) since it's a permission a user/friend reviewing the app might reasonably ask about.

### 2.3 Connection lifecycle — connect-on-demand (DECIDED, battery-conscious)

**The Baileys socket is NOT kept alive continuously.** It connects only in a short window around an actual due message, then disconnects. This is the single biggest lever for keeping the app battery-light.

1. `AlarmManager` fires a configurable warm-up window **before** the earliest due message — named constant `SEND_WARMUP_WINDOW_MS`, default 2 minutes. This is a *soft target*, not a guarantee — see §6.9 for why modern Android/OEM behavior means this can't be promised precisely.
2. Foreground Service starts, Node/Baileys connects using the saved session (no fresh pairing needed — see §4.1), sends the due message(s).
3. **Batching:** if multiple messages are due within the same short window, they're sent over the **same** connection session, not one connect/disconnect cycle per message.
4. Once nothing else is due within the next lookahead window, the socket disconnects and the Foreground Service stops (`START_NOT_STICKY`, see §6.6) — no idle socket, no idle service.
5. The `WorkManager` backstop reuses this same connect → send-what's-due → disconnect pattern, rather than polling in a loop.

Net effect: on a typical day, the app is only ever "active" for a few minutes total around each send — not running continuously.

### 2.4 UI Reference Mockup (DECIDED — replicate this exactly)

A React/JSX visual reference has been built and approved by the user: `wa-scheduler-ui-preview.jsx`. **The coding agent must treat this file as the authoritative visual spec for the Compose UI, not just inspiration.** Replicate its layout, spacing, component shapes, and the Dusty Rose + White palette (§3 Tech Stack table) as closely as Compose allows.

Specifically replicate in Compose:
- **Queue/Home screen**: header with small eyebrow label ("Delivra") + large title ("Queue"), a notification bell icon button (top-right, rounded-square soft-rose background), a search bar (soft rose-tinted background, rounded, search icon + placeholder), a 3-up stats strip (Pending / Sent today / Failed — each a small rounded card with a bold number, label, and a thin colored progress-bar-style underline), then a scrollable list of message cards. Each card: rounded avatar square with initials on soft-rose background, contact name, date+time (right-aligned, muted), a preview line with a small type icon (mic for voice note, file icon for attachment) before it, and below that a row with the status pill (colored dot + label, pill-shaped, soft background tint) on the left. **When status is Pending, a small square cancel/delete icon button (trash icon, soft brick-red-tinted background, brick-red icon) appears on the right side of that same row** — tapping it cancels the AlarmManager alarm and deletes the row/file per §4.7. This button must NOT appear on Sent/Failed/Sending/Needs Review cards. A floating action button (rounded-square, not fully circular, rose gradient, "+" icon, soft rose shadow) sits bottom-right.
- **New Message screen**: back-chevron header, then vertically stacked labeled sections (uppercase small muted section labels: "To", "Message", "Attach", "Send at") — a selected-contact chip with avatar+name+remove(x), a message textarea styled as a soft rose-tinted rounded box, two side-by-side attach cards (voice note dashed-border card, file-attached solid soft-rose card), two side-by-side date/time picker chips with icons, and a bottom-anchored full-width gradient CTA button ("Schedule Message") with a soft rose shadow.
- **`Needs Review` status (added after §6.2's expanded state machine):** uses a muted mustard/ochre pill (`bg #F6EFD9`, `fg #96771A`, accent `#C9A227`) — distinct from Pending's amber and Failed's brick-red — with a small alert-triangle icon in place of the usual status dot, since this state is semantically different (ambiguous outcome, needs the user's attention) rather than a plain good/bad result. The stats strip (Pending/Sent/Failed/Review) reflects its count alongside the other three. Like Pending/Sent/Failed, this status does NOT show the cancel/delete button on its card.
- General style rules to carry through the whole app: generous corner radius (14–18dp) on cards/buttons, no pure black anywhere (use the warm dark neutral `#332E2B` for text/icons), soft tinted backgrounds (`#F7EDEB`/`#FAF5F4` family) instead of plain white/gray for secondary surfaces, status colors exactly as defined in §3, and a rose-gradient (`#D8A7A0` → `#C08079`) reserved specifically for primary CTAs/FAB with a soft rose-tinted shadow beneath it for depth.

### 2.5 Battery & Lightweight Engineering (DECIDED — non-negotiable priority, apply everywhere)

The user has been explicit twice: **this app should be as lightweight as possible, on disk, in memory, and on battery.** Concrete rules:

**Battery:**
- Connect-on-demand only (§2.3) — no always-on socket, no idle Foreground Service.
- `WorkManager` jobs must declare real constraints (`NetworkType.CONNECTED` at minimum) so they don't wake up and immediately fail/retry when offline.
- No polling loops anywhere in the codebase — every trigger is event-driven (`AlarmManager` fire, `WorkManager` run, a bridge callback, `BOOT_COMPLETED`), never a `while(true) { sleep(); check() }` pattern.
- No wake locks held longer than the minimum needed to complete a send; released immediately after.
- The Foreground Service notification uses a low-importance, silent notification channel.

**Size / memory / code weight:**
- Enable R8/ProGuard minification and resource shrinking in the release build (`isMinifyEnabled = true`, `isShrinkResources = true`).
- Keep dependencies minimal — don't add a library for something a few lines of standard Kotlin/Compose or a small Node module already does.
- The message list (Queue screen) must be lazily loaded (`LazyColumn`).
- No large objects (full file bytes, big bitmaps) held in memory longer than the operation needs — stream file reads where possible.
- Auto-cleanup (§4.8) already deletes sent/failed data within 12–24h — don't undermine that by caching or logging things elsewhere that never get cleaned up. This directly shapes the observability design in §6.9: diagnostics must be near-zero persistent footprint by design, not an afterthought bolted onto a "lightweight" app.
- **Attachments are NOT copied into app storage (DECIDED, user's explicit call given limited phone storage) — see §2.7.** This is a deliberate trade-off: it saves significant space, at the cost of the app depending on the source file still existing at send time. §2.7 and §6.5 define how that risk is handled without silent failure.
- The Node/`nodejs-mobile-android` + Baileys layer is the one inherently non-trivial size cost in this stack (bundling a Node runtime is tens of MB) — accepted, unavoidable tradeoff of using Baileys at all. Keep everything else thin to offset it.

### 2.6 Time semantics — wall-clock time (DECIDED, user's own call)

**Decision: `WALL_CLOCK_TIME` behavior, not fixed-instant.** When the user schedules "9:00 AM," they mean *whatever 9:00 AM local time is when that day arrives* — not a UTC instant frozen at scheduling time. Concretely:

- Store `scheduledLocalDateTime` (an ISO-8601 local datetime string with no offset, e.g. `2026-08-25T09:00:00`) and `timezoneId` (e.g. `Asia/Karachi`, from `ZoneId.systemDefault()` at scheduling time) as the source of truth.
- Derive `resolvedEpochMs` from those two at the moment it's needed (when registering the `AlarmManager` alarm, and again whenever alarms are re-registered, e.g. on boot per §2.2 or if the device's timezone changes).
- **Practical effect:** if the user travels and their phone's timezone changes before the scheduled time arrives, the message still fires at 9:00 AM in whatever timezone the phone is currently in — not 9:00 AM in the original timezone. This matches the user's own stated intent and is simpler to reason about than trying to track "the original instant."
- DST transitions: since this is wall-clock, not fixed-instant, a message scheduled for a wall-clock time that a DST transition skips or repeats should resolve using the platform's standard `ZonedDateTime` disambiguation (Java time handles this natively) rather than custom logic.
- This is locked in as a v1 decision — do not build a `FIXED_INSTANT` alternative mode; that would just be unused complexity given the user's explicit preference.

### 2.7 Attachment handling — no duplication, persistent URI access (DECIDED, user's explicit override of the "copy to app storage" default)

**Decision: do NOT copy attachment files into app-private storage.** The user has limited phone storage and was explicit that duplicating an existing PDF/Word file the user already has elsewhere on their phone is not acceptable just to make the scheduling pipeline simpler. Instead:

1. User picks the file via the Android system file picker (Storage Access Framework), which returns a `content://` URI pointing at the file **in its original location**.
2. Immediately call `context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)` so the app retains read access to that exact file long-term, without copying its bytes anywhere.
3. Store in Room: the URI string, display name, MIME type, and size (all cheap metadata reads via `ContentResolver.query`, not the file contents) — see updated schema in §5.
4. **Checksum/hash of the file content is intentionally skipped for v1** — computing one would mean reading the whole file at attach time, which cuts against the "very very lightweight" priority (§2.5) for a benefit (detecting silent file corruption) that's a fairly rare edge case. Existence + readability checks (below) cover the realistic failure modes.
5. **Validate readability twice, not once:** once at attach time (fail fast, tell the user immediately if the picker somehow returned something unreadable), and again **right before the actual send** (a file can be moved, deleted, or its permission revoked at any point between scheduling and send time — sometimes days later).
6. If the send-time re-validation fails (`FileNotFoundException`, `SecurityException` from a revoked grant, etc.): mark the message `FINAL_FAILURE` (§6.2) with `lastErrorReason = "source_file_unavailable"` and fire the failure notification (§4.9) — do not silently drop it, and do not retry blindly, since re-reading the same missing file will just fail again.
   - **One exception:** if the failure looks like a removable-storage-not-mounted case (file lives on an SD card path that's temporarily inaccessible), allow exactly one retry after a short delay before giving up — this is a genuinely transient case, unlike a deleted/moved file.
7. Voice notes are different from this concern: they're either recorded fresh in-app or transcoded from an upload (§4.3), so they're already app-owned, newly-created files, not duplicates of something the user has elsewhere. These continue to live in app-private storage and follow the normal 12h/24h cleanup (§4.8) — no space concern the same way PDFs/Word docs from the user's existing files would have.

---

## 3. Tech Stack

| Layer | Technology |
|---|---|
| WhatsApp connection | [Baileys](https://github.com/WhiskeySockets/Baileys) (`@whiskeysockets/baileys`) — unofficial WhatsApp Web multi-device protocol library |
| Node runtime inside Android | [nodejs-mobile-android](https://github.com/nodejs-mobile/nodejs-mobile) |
| Data persistence | **Room (Android/Kotlin)**. See §2.1. |
| Scheduling trigger | **AlarmManager** (`setExactAndAllowWhileIdle`) for exact wakeups + **WorkManager** (periodic, ~15 min) as backstop/cleanup/reconciliation + **`BOOT_COMPLETED` receiver** for reboot recovery (§2.2) |
| Time handling | **DECIDED — wall-clock semantics**, not fixed-instant. See §2.6. `java.time` (`ZonedDateTime`/`ZoneId`) for all conversions — no manual offset math. |
| Attachment access | **DECIDED — persistent URI permission, no file copying.** See §2.7. `ContentResolver` + `takePersistableUriPermission`, not internal file copies. |
| Native shell / UI | Kotlin + Jetpack Compose, MVVM + Repository pattern, `StateFlow`/`Flow` for reactive UI. **Theme: DECIDED — Dusty Rose + White.** Muted blush/rose (e.g. `#D8A7A0`–`#C98F8A` range) as the primary accent, clean white (`#FFFFFF`) / near-white (`#FAFAFA`) as the base surface, with a soft dark neutral (e.g. `#3A3532`, not pure black) for body text/icons. Explicitly NOT black/dark-surface theme, NOT green, NOT purple/indigo/lavender, NOT neon blue. Define once in `Color.kt`/`Theme.kt` as a custom Material 3 color scheme. Status badges use distinguishable colors within this palette (warm amber = Pending, sage/rose = Sent, muted brick-red = Failed, muted mustard/ochre = Needs Review per §6.2) rather than default Material red/green/yellow. |
| Background execution | Android Foreground Service (hosts Node runtime + Baileys socket), `START_NOT_STICKY` (§6.6) |
| Contact source | **DECIDED:** Baileys-synced WhatsApp contacts only (equivalent to WhatsApp's own "New Chat" list) — not raw Android `ContactsContract`, no `READ_CONTACTS` permission needed. Name-searchable. Resolved to a WhatsApp JID **at scheduling time** (not deferred to send time — see §6.3). |
| CI/CD | GitHub Actions — builds APK on push/tag, attaches to GitHub Release. Runs inside the Docker build image below, not a bare `ubuntu-latest` runner with ad-hoc SDK installs. |
| Reproducible build environment | **DECIDED — Dockerized build, not a Dockerized app.** A `Dockerfile` bundling Android SDK, Android NDK (needed for `nodejs-mobile-android`'s native bits), Gradle, and Node.js/npm — so anyone can build the APK with a single `docker build` / `docker run` command. GitHub Actions CI uses this **same** image, so local and CI builds are guaranteed identical. |
| Distribution | GitHub Releases only, **unsigned APK for v1** (DECIDED — simplest for an open-source personal project; revisit signing only if distribution grows beyond friends) |

---

## 4. Core Features (confirmed with user)

1. **WhatsApp linking**: **DECIDED — pairing code, not QR.** Since the app and WhatsApp both run on the same single phone, a QR flow is physically impractical. Instead, use Baileys' `requestPairingCode(phoneNumber)`: user enters their WhatsApp number in-app, the app displays an 8-character code, and the user manually enters it via WhatsApp → Settings → Linked Devices → Link a Device → "Link with phone number instead." This is a **one-time manual step** (Meta requires manual confirmation for any new linked device). Session persists locally (Baileys multi-file auth state stored in app-private storage, written atomically — see §6.1) — no repeat linking unless logged out.
2. **Contact picker**: search/select recipient **by name**, resolved to a JID immediately and stored with the scheduled message (not re-resolved at send time). **Source is exclusively Baileys' synced WhatsApp contacts** — the same set WhatsApp itself shows under "New Chat" (phone contacts that have WhatsApp), not limited to contacts the user has an existing chat with. This is explicitly NOT the raw Android `ContactsContract` API — the app does not request `READ_CONTACTS` permission at all, since contact data comes through the WhatsApp linked-device protocol instead.
3. **Message content**, any combination:
   - Text
   - Voice note (recorded in-app or uploaded) — sent as a genuine WhatsApp PTT voice note (`ptt: true` in Baileys, proper waveform bubble), not a generic audio attachment. Audio must be transcoded to Opus/OGG if the source isn't already in that format (see §6.4a).
   - PDF or Word (.docx) attachment — accessed via persistent URI, not copied (§2.7).
4. **Multiple scheduled messages**: unlimited, same or different contacts, independent times each.
5. **Scheduling**: exact wall-clock date/time (§2.6); fires automatically per §2.3, screen-off/backgrounded/rebooted included.
6. **Status per message**, UI-facing: `Pending ⏳` / `Sending 🔄` / `Sent ✅` / `Failed ❌` / `Needs Review ⚠️` (see §6.2 for the full internal state machine these map to, and why `Needs Review` exists as a distinct 5th state).
7. **Manual delete**: available for `Pending` messages — cancels the AlarmManager alarm and deletes the Room row (and any app-owned voice-note file) immediately. No file deletion needed for attachments since they were never copied (§2.7).
8. **Auto-cleanup (storage-conscious, explicit user priority)**:
   - `Sent`: delete Room row + any app-owned voice-note file **12 hours** after successful send.
   - `Failed`: delete Room row + file **24 hours** after final failure.
   - `Needs Review`: same 24-hour window as Failed, but only after the user has either acknowledged it or the window elapses — see §6.2 for why this state needs slightly different handling.
   - No history/log retained after cleanup — intentional, not an oversight. Reconciled with observability needs in §6.9.
9. **Failure notification**: when a message transitions to `Failed` or `Needs Review`, post a standard Android system notification (via `NotificationCompat`, its own notification channel) showing the contact name and a short reason. Fires even if the app UI isn't open. Tapping opens the app to that message.
10. **App icon**: user sources this themselves from the internet. Agent must leave standard placeholder slots in `res/mipmap-*/` and must not invent a specific icon design.
11. **Standard OS uninstall only**: no in-app uninstall button. App must not request Device Admin or any permission that would block normal uninstall.

---

## 5. Database Schema (Room)

```kotlin
enum class MessageStatus {
    PENDING,            // scheduled, not yet claimed by any dispatch cycle
    CLAIMED,            // atomically claimed by one dispatch cycle, about to connect — nothing sent yet, safe to revert to PENDING on failure here
    CONNECTING,         // Baileys socket authenticating — nothing sent yet, safe to revert to PENDING on failure here
    SENDING,            // actual send call in flight — ambiguous if interrupted here, see §6.2/§6.7
    SENT_CONFIRMED,      // Baileys ack received AND persisted — UI shows "Sent"
    RETRYABLE_FAILURE,  // transient failure, will auto-retry with backoff, then either back to PENDING or promoted to FINAL_FAILURE
    FINAL_FAILURE,       // exhausted retries or non-retryable error — UI shows "Failed"
    NEEDS_REVIEW         // ambiguous outcome after a crash/kill during SENDING, found on reconciliation — UI shows "Needs Review", requires manual user resolution (§6.2)
}

@Entity(tableName = "scheduled_messages")
data class ScheduledMessage(
    @PrimaryKey val id: String = UUID.randomUUID().toString(), // also used as correlationId, see §6.2
    val contactName: String,
    val contactJid: String,                 // resolved at creation time, not send time

    val messageText: String?,
    val voiceNotePath: String?,              // app-owned file, app-private storage
    val attachmentUri: String?,              // content:// URI, persistent permission taken — NOT a copied file, see §2.7
    val attachmentDisplayName: String?,
    val attachmentMimeType: String?,         // "application/pdf" | "application/vnd.openxmlformats-officedocument.wordprocessingml.document" | null
    val attachmentSizeBytes: Long?,

    val scheduledLocalDateTime: String,      // ISO-8601 local, no offset, e.g. "2026-08-25T09:00:00" — see §2.6
    val timezoneId: String,                  // e.g. "Asia/Karachi", captured at scheduling time
    val resolvedEpochMs: Long,               // derived from the two fields above, re-derived on boot/timezone change

    val status: MessageStatus,
    val attemptCount: Int = 0,
    val attemptStartedAtEpochMs: Long? = null, // when the current/last attempt began — used to detect staleness for reconciliation (§6.7)
    val engineSessionId: String? = null,       // which Baileys connect() session handled the last attempt — diagnostics only
    val lastErrorReason: String? = null,

    val terminalAtEpochMs: Long? = null,     // set when status reaches SENT_CONFIRMED, FINAL_FAILURE, or NEEDS_REVIEW — drives the 12h/24h cleanup windows
    val createdAtEpochMs: Long
)
```

Cleanup query logic (run inside the WorkManager periodic job):
```
DELETE files + rows WHERE status = SENT_CONFIRMED           AND terminalAtEpochMs <= now - 12h
DELETE files + rows WHERE status = FINAL_FAILURE             AND terminalAtEpochMs <= now - 24h
DELETE files + rows WHERE status = NEEDS_REVIEW               AND terminalAtEpochMs <= now - 24h
```

---

## 6. Error Handling

### 6.1 Connection resilience (Node/Baileys layer)
- On socket drop, reconnect with **exponential backoff** (e.g. 2s, 4s, 8s... capped at ~60s), not a tight retry loop.
- Distinguish Baileys' `DisconnectReason`:
  - `loggedOut` → session is dead. Do NOT auto-retry. Surface a clear "Re-link WhatsApp" state in the UI and block sending until the user generates and enters a new pairing code (§4.1).
  - anything else (network blip, `connectionClose`, `restartRequired`, etc.) → auto-reconnect per backoff above.
- Bridge layer must expose current connection state (`connected` / `connecting` / `loggedOut` / `error`) to Kotlin.
- **Auth-state write safety (DECIDED — required):** Baileys' multi-file auth state must be written using a **temp-file + atomic rename** pattern, not direct in-place writes — a process kill mid-write is a realistic scenario (this app deliberately starts/stops its process frequently per §2.3) and a torn write can corrupt the session file.
- **Startup session health check:** on every connect attempt, validate the auth state files are readable/parseable before attempting to use them. If corrupt: do NOT enter a silent infinite reconnect loop. Instead, treat it the same as `loggedOut` — surface "Re-link WhatsApp required" in the UI and pause all sending until resolved.

### 6.2 Send-time retry + duplicate-send guard (expanded state machine)
- A message moves `PENDING → CLAIMED` via an **atomic Room transaction** (`UPDATE ... WHERE id = ? AND status = 'PENDING'`) before any send work begins. This closes the race where both the exact AlarmManager alarm and the WorkManager backstop fire near the same time.
- `CLAIMED → CONNECTING → SENDING` as the attempt progresses. **Why these are separate states, not just "Sending":** it matters *where* an interruption happens. If the process dies while still `CLAIMED` or `CONNECTING`, nothing was ever sent — safe to revert to `PENDING` and retry normally. If it dies while `SENDING` (the actual Baileys send call was in flight), the outcome is genuinely unknown — WhatsApp may or may not have received it. This ambiguity is exactly what causes duplicate-send risk if handled carelessly.
- **Ambiguous-outcome handling (this is the core idempotency fix):** a row found stuck in `SENDING` during reconciliation (§6.7) — i.e. not the row currently being actively processed by the live dispatch cycle — is transitioned to `NEEDS_REVIEW`, **not** silently retried and **not** silently marked failed. Both alternatives are wrong: auto-retry risks a duplicate message reaching the recipient; auto-fail risks hiding a message that actually went through. `NEEDS_REVIEW` surfaces this to the user with a short explanation ("We're not sure if this sent — check WhatsApp") and lets them mark it resolved (delete) or manually resend. This is the safe, honest default given the ambiguity is real, not a corner the agent should engineer around with guesswork.
- Rows stuck in `CLAIMED` or `CONNECTING` (nothing sent yet) during reconciliation are safely reverted to `PENDING` for a normal retry — no ambiguity there.
- On a genuine send failure (not a crash — an actual error response), classify it: transient errors (`network_timeout`, `connection_closed`) → `RETRYABLE_FAILURE`, non-retryable errors (`invalid_jid`, `media_too_large`) → straight to `FINAL_FAILURE` without wasting retries on something that will predictably fail the same way again.
- `RETRYABLE_FAILURE` retries up to **3 attempts** with short backoff (e.g. 5s, 20s, 60s) before promotion to `FINAL_FAILURE`. `attemptCount` and `lastErrorReason` are stored throughout so a failure is diagnosable.
- Each attempt's `id` (the row's own UUID, §5) doubles as a **correlation ID** passed through to the Node/bridge layer and, where Baileys supports it, as the outgoing message's client-set key ID — this is a best-effort traceability aid for diagnostics (§6.9), not a guaranteed server-side dedup mechanism, since WhatsApp's own protocol doesn't promise dedup purely from a client-supplied ID.

### 6.3 Contact resolution
- Resolved to a JID **once, at scheduling time**, not re-looked-up at send time.
- If the name search matches multiple contacts, the UI must force disambiguation before the message can be saved.
- If Baileys contact sync hasn't completed yet, the contact picker should show a loading state.

### 6.4 Missed schedules (phone off / app killed at due time)
**DECIDED default (configurable, not silent):** if a `PENDING` message is discovered overdue by the WorkManager backstop or the boot-recovery pass (§2.2):
- If overdue by **≤ 6 hours**: send immediately when discovered.
- If overdue by **> 6 hours**: do NOT send silently late. Mark `FINAL_FAILURE` with `lastErrorReason = "missed_window_exceeded"` and post the failure notification.
- This 6-hour threshold is a named constant (`MISSED_SCHEDULE_GRACE_MS`), not a magic number scattered through the code.

### 6.4a Media validation (before scheduling, not at send time — fail fast)
- Validate PDF/Word file readability + size against WhatsApp's practical media limits at the moment the user attaches it (§2.7 point 5), and warn in the UI immediately.
- Voice notes: if the recorded/uploaded audio isn't already Opus/OGG, transcode it at attach-time, not send-time. Define explicitly: transcode failure → block scheduling with a clear error (don't let a broken voice note get scheduled); enforce a duration/size cap; clean up any temporary transcoding artifacts immediately after the final file is produced, not left for the 12h/24h cleanup to catch.

### 6.5 Source-file availability at send time
See §2.7 point 6 for the full policy — re-validated right before send, `FINAL_FAILURE` with `source_file_unavailable` on failure (one transient-storage retry allowed, no blind repeated retries on a genuinely missing file).

### 6.6 Service resilience
- Foreground Service uses `START_NOT_STICKY`, **not** `START_STICKY` — the service is only supposed to be alive around an actual due send; auto-restarting it with no work to do would waste battery. Restart-when-needed is `AlarmManager`'s (and `BootReceiver`'s) job, not the service's.
- On first run, explicitly prompt the user through the checks in §6.9 (exact-alarm permission, battery optimization exemption, OEM autostart guidance).

### 6.7 Reboot & process-death reconciliation (DECIDED — required)
Runs on: app boot (`BootReceiver`), every `WorkManager` backstop cycle, and Foreground Service startup.

Logic:
- Any row in `CLAIMED` or `CONNECTING` whose `attemptStartedAtEpochMs` is older than a short staleness threshold (e.g. 2 minutes, named constant `CLAIM_STALENESS_MS`) → safe to revert to `PENDING`, since nothing was actually sent yet.
- Any row in `SENDING` past that same staleness threshold → transition to `NEEDS_REVIEW` (§6.2) — this is the ambiguous case, handled conservatively.
- This reconciliation pass is what actually closes the duplicate-send risk that a naive "just retry on next boot" approach would reopen — it's not enough to just reschedule alarms (§2.2 point 5), the in-flight state has to be reconciled too, which is why this is a separate, explicitly named step.

### 6.8 Concurrency model — single dispatcher (DECIDED)
- Only one dispatch cycle (the logic that claims and processes due messages) may run at a time, even if triggered from multiple sources (an `AlarmManager` fire and a `WorkManager` run landing close together, for instance).
- Implement via `WorkManager`'s unique work (`enqueueUniqueWork(..., ExistingWorkPolicy.KEEP, ...)`) for the backstop/dispatch path, and have the `AlarmReceiver` also route through the same unique-work entry point rather than starting a second independent execution path — this way there's structurally one dispatcher, not a lock that has to be remembered and correctly released everywhere.
- The atomic `PENDING → CLAIMED` transaction (§6.2) remains the row-level safety net underneath this — the unique-work constraint prevents *most* races from occurring at all, and the atomic claim handles the rest.

### 6.9 Observability & diagnostics (kept deliberately lightweight — see §2.5)
Reconciling "we need to debug this" with "the app must stay very lightweight" — the design is intentionally not a persisted event-log table:
- Each `ScheduledMessage` row already carries enough state for basic diagnosis without a separate events table: `status`, `attemptCount`, `attemptStartedAtEpochMs`, `engineSessionId`, `lastErrorReason` (§5). This is sufficient for "what happened to this specific message" without unbounded storage growth.
- A small **in-memory only** ring buffer (fixed size, e.g. last 50 events, never written to disk) captures fine-grained events (claimed → connecting → connected → send requested → ack received, etc.) for live debugging during an active session. It's wiped on process death — by design, not an oversight, since persisting it would work against §2.5.
- A **Diagnostics screen** in Settings shows, all as **live queries, not stored history**: current connection state, next scheduled alarm time(s), battery optimization exemption status, exact-alarm permission status (`AlarmManager.canScheduleExactAlarms()` on API 31+), and the in-memory ring buffer above.
- An "Export diagnostics" action dumps the current live state + ring buffer to a temporary text file for sharing when reporting a bug — generated on demand, not retained afterward.

### 6.10 Exact-alarm reality on modern Android (realistic expectations, not just a code fix)
The architecture assumes: alarm fires → service starts → Node boots → Baileys connects → send happens, all within the warm-up window (§2.3). This is an optimistic chain — OEM battery management (Samsung included) can still delay or drop these wakeups even with everything implemented correctly. Handle this honestly rather than architecting around a promise the OS doesn't make:
- **Onboarding must explicitly check and request, not just hope for:**
  - Exact-alarm scheduling permission (Android 12+/API 31+ — `AlarmManager.canScheduleExactAlarms()`, prompting via `ACTION_REQUEST_SCHEDULE_EXACT_ALARM` if not granted).
  - Battery optimization exemption (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`).
  - OEM-specific autostart/background permission where detectable (Samsung's Device Care "Put unused apps to sleep" / autostart list can't be requested programmatically — show manual guidance with a deep link to the relevant settings screen when the OEM is detected as Samsung).
- If any of these checks aren't satisfied, show a persistent (non-naggy) status indicator on the Home screen — "This device may delay scheduled sends — tap to fix" — rather than silently assuming best-case behavior.
- README and in-app copy should use realistic language ("aims to send within a few minutes," per §1) rather than promising exact-second delivery.

---

## 7. Known Risks / Honesty Notes (must also appear in the README)

- WhatsApp multi-device linked sessions can expire if the primary phone's WhatsApp goes fully offline for an extended period (historically ~14 days). Normal daily phone use avoids this.
- The 15-minute minimum interval on `WorkManager` periodic jobs is an Android OS limit, not a design flaw.
- The app requests `RECEIVE_BOOT_COMPLETED` (§2.2) — disclose plainly why (rescheduling alarms after reboot, nothing more).
- Exact-time delivery is a best-effort target, not a guarantee, due to OEM battery management (§6.10) — say this plainly rather than overpromising.
- Attachments are read from their original location via a persistent URI grant, not copied (§2.7) — if the user deletes or moves the source file before the scheduled send time, that message will fail with a clear "source file unavailable" status rather than silently succeeding or silently vanishing.
- Ambiguous send outcomes (a crash during the actual send call) surface as `Needs Review` rather than being auto-resolved either way — this is a deliberate conservative choice to avoid duplicate sends, and means the user may occasionally need to manually check WhatsApp for a handful of messages.

---

## 8. Suggested Repo Structure

```
delivra/
├── design-reference/
│   ├── wa-scheduler-ui-preview.jsx              → APPROVED visual reference, see §2.4. Replicate exactly in Compose (includes Needs Review status pill).
├── app/
│   ├── src/main/
│   │   ├── java/.../ui/                        → Compose screens (Home/list, New Message, Pairing/Link, Settings, Diagnostics)
│   │   ├── java/.../data/
│   │   │   ├── ScheduledMessage.kt              → Room entity + MessageStatus enum (§5)
│   │   │   ├── ScheduledMessageDao.kt           → Room DAO, incl. atomic claim query (§6.2) and reconciliation queries (§6.7)
│   │   │   ├── AppDatabase.kt
│   │   │   ├── ScheduleRepository.kt            → single source of truth exposed to ViewModels
│   │   ├── java/.../scheduling/
│   │   │   ├── AlarmScheduler.kt                → wraps AlarmManager exact-alarm calls, resolves wall-clock → epoch (§2.6)
│   │   │   ├── AlarmReceiver.kt                 → BroadcastReceiver, routes into the single dispatcher (§6.8)
│   │   │   ├── BootReceiver.kt                  → RECEIVE_BOOT_COMPLETED: re-registers alarms + triggers reconciliation (§2.2, §6.7)
│   │   │   ├── BackstopWorker.kt                → WorkManager: missed-schedule catch-up, cleanup, reconciliation — unique work (§6.8)
│   │   ├── java/.../service/
│   │   │   ├── SchedulerService.kt              → Foreground Service hosting Node runtime + connect-on-demand lifecycle (§2.3)
│   │   ├── java/.../bridge/
│   │   │   ├── NodeBridge.kt                    → typed request/response wrapper over nodejs-mobile channel, carries correlationId (§6.2)
│   │   ├── java/.../attachments/
│   │   │   ├── AttachmentAccess.kt              → SAF picker + takePersistableUriPermission + readability re-validation (§2.7)
│   │   ├── java/.../diagnostics/
│   │   │   ├── EventRingBuffer.kt               → in-memory only, fixed size (§6.9)
│   │   ├── assets/nodejs-project/
│   │   │   ├── index.js                         → bridge listener, dispatches to whatsapp.js
│   │   │   ├── whatsapp.js                      → Baileys connection, atomic auth-state writes (§6.1), reconnect/backoff logic
│   │   │   ├── sender.js                        → sendMessage(payload) incl. ptt voice note handling
│   │   │   ├── package.json
│   │   ├── res/
│   │   │   ├── mipmap-*/                        → APP ICON PLACEHOLDER, user supplies their own
│   │   │   ├── values/                          → theme (Dusty Rose + White, see §3 Tech Stack table — Color.kt / Theme.kt)
│   ├── build.gradle
├── .github/workflows/build-apk.yml               → CI: build unsigned APK inside Docker image below, attach to GitHub Release
├── Dockerfile                                     → Android SDK + NDK + Gradle + Node.js build environment (§3 Reproducible build environment row)
├── README.md                                      → setup + §7 disclosures + Docker build instructions
├── LICENSE
```

---

## 9. Naming (DECIDED — no longer an open question)

- **App name: Delivra.** Kotlin package ID: `com.kabshah.delivra`. Repo root folder: `delivra/` (§8's structure diagram already reflects this).

---

## 10. Tone/Engineering Preferences for the Agent

- Polished, non-janky end product — real app feel (consistent Compose theming), not a debug tool.
- User is technical (CS/AI student, FYP on ML security/agentic systems) and prefers clean, well-structured engineering over tutorial-shortcut code.
- Minimal storage footprint is a recurring, explicit priority — reflected in §2.5, §2.7, §4.8, §6.4, and §6.9's deliberately lightweight observability design. Do not silently add caching/logging that grows unbounded.
- Favor explicit, named constants and clear state machines over magic numbers or implicit behavior — this file should stay the single source of truth, so decisions belong here, not buried in code comments only.
- Where an outcome is genuinely ambiguous (§6.2's `NEEDS_REVIEW`), the correct engineering response is to surface that ambiguity honestly to the user, not to silently guess in either direction — this principle applies beyond just that one case if similar ambiguity comes up elsewhere during implementation.
