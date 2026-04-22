# SeekerZero — Plan v3 (2026-04-20)

**Supersedes Plan v2** (same file, scope pivot). v2 was an approvals-only thin client (tabs: Approvals / Tasks read-only / Cost / Diagnostics). v3 widens the remit: SeekerZero is now a **full A0 client on mobile** — chat, terminal, scheduled-task management, and approvals — with the Seeker as a first-class command surface equivalent to the web UI. Phase 1 (approvals end-to-end) shipped under the v2 plan and carries over without change; Cost and Diagnostics were cut. Chat, Terminal, and Task write-path are new.

Native Android app on the Solana Seeker phone. Full Agent Zero client over Tailscale. Separate codebase from the benched Seeker dapp project; same device, different job. The Seeker is treated as a normal Android phone — no Solana Mobile Stack, no wallet, no blockchain interaction.

Renamed from "SeekerClaw" early in planning to avoid collision with the existing open-source SeekerClaw project (sepivip/SeekerClaw on GitHub, MIT) and to accurately reflect that this app connects to Agent Zero, not Claude.

---

## Prior art

**SeekerClaw** (sepivip/SeekerClaw, MIT, v1.4.1 Feb 2026) is an architecturally opposite project targeting the same device. SeekerClaw embeds a Node.js runtime in the APK and runs an on-device agent supervised by a foreground service; the user interacts through Telegram. SeekerZero keeps the agent remote (Agent Zero on a0prod) and makes the phone a thin client over Tailscale.

We copy from SeekerClaw: the build.gradle.kts skeleton (minus Node, NDK, Firebase, Solana, flavors), the `SeekerClawApplication.kt` two-notification-channel pattern, the `MainActivity.kt` entry point (trivial), the manifest service declaration shape, the QR payload format (minus the encryption), the `specialUse` foreground service type decision, the watchdog timing cadence, the UI component library structure from their `UI_COMPONENT_AUDIT.md`, the retry/reconnection patterns from their `NETWORK_RESILIENCE_PLAN.md`, the offline-gap recovery pattern from their `MISSED_TASK_NOTIFICATION_PLAN.md`, and the `CLAUDE.md` / `PROJECT.md` / `SETTINGS_INFO.md` / `TEMPLATES.md` documentation conventions.

We do **not** copy: the Node.js runtime, the `:node` subprocess, the cross-process file-based IPC in their `ServiceState.kt` (300 lines we don't need because we don't have a subprocess), Claude API integration, Telegram as the primary UX, on-device skills/memory/MCP, on-device database, Solana wallet, Firebase, product flavors, public distribution, or config encryption (no secrets to protect — Tailscale identity is the auth).

MIT license permits copying code with attribution. Any non-trivial snippet taken verbatim goes in a `NOTICES` file at the repo root.

---

## Locked decisions

| Area | Decision |
|---|---|
| **Transport** | Tailscale. Seeker joins existing tailnet, talks directly to a0prod at `a0prod.<your-tailnet>.ts.net`. No relay, no separate WireGuard layer. |
| **Auth** | Tailscale device identity. No app-level login, no tokens, no API keys. Device on the tailnet = authorized. |
| **Stack** | Native Kotlin + Jetpack Compose + Material 3. Android Studio. No cross-platform framework. |
| **Solana Mobile SDK** | Not used. SeekerZero does not touch the blockchain or wallets. Seeker is a normal Android device. |
| **Android target** | compileSdk 35, minSdk 34 (Android 14), targetSdk 35, Java 17, Kotlin 2.0. Matches SeekerClaw's exact versions. |
| **A0 client identity** | SeekerZero registered as a first-class Agent Zero client peer to `webui` and `telegram`. Own identity record, own audit trail, own entry in approval-gate routing. |
| **Server-side surface** | Dedicated `/mobile/*` JSON API namespace on a0prod. Versioned independently from web UI. |
| **Foreground service type** | `specialUse` with matching `FOREGROUND_SERVICE_SPECIAL_USE` permission. Sideload-only, no Play Store justification burden. |
| **Process model** | Service runs in the **main app process**. No `:node` subprocess. No cross-process file-based IPC. All state in-memory `StateFlow`. |
| **Background model** | Foreground service with long-poll over Tailscale. **User-controlled toggle** (Tailscale-Android pattern) — flip on, persistent notification + live approval pings; flip off, app is open-only. No FCM, no Google push dependency. |
| **Notification channels** | Two, created in `Application.onCreate`: `seekerzero_service` (LOW, silent, persistent) and `seekerzero_approvals` (HIGH, sound, actionable). |
| **Boot behavior** | `BootReceiver` restores the toggle state after first unlock. If toggle was on before reboot, service auto-starts; if off, stays off. |
| **Telemetry** | **None.** No Firebase, no Crashlytics, no analytics of any kind. LogCollector is in-memory + local log file only. |
| **Distribution** | Sideload via ADB. Single build flavor, single signing config. No Play Store, no dApp Store. |
| **v1 surfaces** | (1) Chat, (2) Approvals, (3) Tasks (read + create), (4) Terminal (SSH pty to a0prod). |
| **Sequencing** | Approvals (**shipped**) → Chat → Terminal → Tasks read+write. Each independently shippable. |
| **SSH key provisioning** | Per-device Ed25519 keypair generated in Android Keystore on first run. Public key shown on SetupScreen; user pastes into `~/.ssh/authorized_keys` on a0prod once. Private key never leaves the phone. Hardware-backed where the Seeker's StrongBox allows. |
| **Chat persistence** | Source of truth on a0prod; phone caches the recent N messages locally (Room DB) for instant-open UX. On reconnect the phone fetches the tail since its last-known message id and merges. |
| **Repo conventions** | `CLAUDE.md` + `PROJECT.md` split. `docs/internal/SETTINGS_INFO.md` mirroring `SettingsHelpTexts.kt`. `docs/internal/TEMPLATES.md` mirroring Android `strings.xml`. |

---

## Server-side: the `/mobile/*` API namespace on a0prod

New namespace, JSON contract, lives alongside the existing web UI routes. SeekerZero is the first consumer; the namespace is designed to outlive it.

### Identity and registration

Register `seekerzero` as a client type in Agent Zero's client registry alongside `webui` and `telegram`. The approval-gate routing table learns to dispatch to SeekerZero clients. Audit logs tag origin = `seekerzero` for any action taken via the mobile API.

### Multi-client approval coexistence

Approval gates broadcast to all registered clients (Telegram + SeekerZero + webui). First resolution wins. Other clients get a "resolved elsewhere" dismissal push and silently update their local state. Two phones buzzing for the same approval is fine; two channels racing to resolve it is not. The server records which client resolved each gate in the audit trail.

### Endpoints (v1)

**Health** (always-on)
- `GET /mobile/health` — reachability + fleet snapshot. Used by setup, reconnect watchdog, and diagnostics surface wherever it lives.

**Approvals** (Phase 1 — shipped)
- `GET /mobile/approvals/pending` — list open approval gates
- `GET /mobile/approvals/stream?since=<ms>` — long-poll endpoint, 60s server-held hold. When `since` is provided (on reconnect), returns everything raised during the gap first (capped at 24h lookback) then falls into normal long-poll behavior. Without `since`, behaves as normal stream.
- `POST /mobile/approvals/{id}/approve` — approve a gate
- `POST /mobile/approvals/{id}/reject` — reject a gate
- `GET /mobile/approvals/{id}` — detail for one gate (not yet implemented; cards self-contain enough for now)

**Chat** (Phase 5)
- `GET /mobile/chat/history?limit=<n>&before_id=<id>` — paged message history, newest-first, reverse-chronological. Default `limit=50`, cap 200. `before_id` drives infinite-scroll into older messages.
- `POST /mobile/chat/send` — submit a user prompt. Body: `{text, context_id?}`. Returns `{message_id, created_at_ms}` immediately; the assistant's streamed response lands on the stream endpoint below.
- `GET /mobile/chat/stream?since_id=<id>` — long-poll for new chat events (user echoes + assistant token deltas + assistant turn-complete). Same 60s hold shape as approvals/stream. Events carry `message_id`, `role`, `delta`, `is_final`. Token-granular streaming so the phone can render tokens as they arrive.
- `GET /mobile/chat/contexts` — list of active conversation contexts (A0 may route chats through multiple contexts; v1 defaults to a single "mobile" context).

**Tasks** (Phase 7 — read + create, edit stays v2)
- `GET /mobile/tasks/scheduled` — list scheduled tasks with last-run status and next-run time
- `GET /mobile/tasks/{id}` — detail for one task (cron, prompt, profile, history)
- `POST /mobile/tasks` — create a scheduled task. Body: `{name, prompt, cron, profile?, state?}`. Server validates cron + profile.
- `POST /mobile/tasks/{id}/run` — trigger an immediate ad-hoc run of a scheduled task (does not change its schedule)
- `POST /mobile/tasks/{id}/enable` + `POST /mobile/tasks/{id}/disable` — toggle `state` without editing the task body

**Terminal** (Phase 6)
- No new `/mobile/*` endpoints. The phone SSHes to a0prod directly over the tailnet on port 22 using the per-device Ed25519 key. Agent Zero does not mediate the terminal session; this is a normal SSH session to the host.

**Config handoff**
- `GET /mobile/config/qr` — renders a scannable QR (PNG or data URI) encoding the SeekerZero config payload. Accessed from a browser on a0prod during first-run setup. (Not yet implemented server-side; dev flow uses `tools/generate_qr.py` until it is.)

### Auth on the server side

Tailscale identity is already trusted at the network layer. `/mobile/*` checks the request comes from an authorized tailnet peer and tags it as a SeekerZero session. No tokens, no headers, no app secret.

### Capped lookback rule

All `since=<ms>` queries are capped at 24 hours. Anything older is considered stale. The user can still see older approvals by opening the Approvals tab, which pulls from `/mobile/approvals/pending`.

---

## Android app architecture

### Stack

- Kotlin 2.0, Jetpack Compose, Material 3, dark theme only
- Android target: compileSdk 35, minSdk 34 (Android 14), targetSdk 35
- Java 17
- HTTP: Ktor or OkHttp (decide at scaffolding)
- JSON: kotlinx.serialization
- QR scanning: ZXing (`com.journeyapps:zxing-android-embedded`). Pure Kotlin/Java, no Google Play Services dependency, consistent with the "no Google telemetry surface" posture. CameraX is used by ZXing internally.
- No NDK, no CMake, no native code
- No Firebase, no analytics, no telemetry

### Process model

Single process. Foreground service runs in the **main app process**, not a subprocess. All state lives in-memory `StateFlow`, observed by the UI directly. No file-based IPC, no polling, no process-name guards, no TOCTOU locks. This is a deliberate simplification relative to SeekerClaw, whose `ServiceState.kt` is ~330 lines primarily because their service runs in `:node` process for nodejs-mobile reasons. We have no such constraint.

**Explicit invariant (put in CLAUDE.md):** Do not introduce cross-process IPC. If a future feature needs background work, it runs in the same process or gets rejected.

### Architecture sketch

```
MainActivity (Compose entry point, trivial)
  ↓
SeekerZeroNavHost
  ├── SetupScreen (first-run only: QR + SSH pubkey enrollment)
  └── MainScaffold (4 bottom tabs)
      ├── ChatScreen           (Phase 5)
      ├── ApprovalsScreen      (Phase 1, shipped)
      ├── TasksScreen          (Phase 7: read + create)
      └── TerminalScreen       (Phase 6: SSH pty)

SeekerZeroService (foreground, specialUse)
  ├── ApprovalsLongPoll    → /mobile/approvals/stream
  ├── ChatLongPoll         → /mobile/chat/stream (only while Chat is open or unread > 0)
  ├── ConnectionWatchdog   → retry/reconnection policy, NetworkCallback
  └── NotificationManager  → two channels (service + approvals)

ChatRepository
  ├── Room-backed cache (recent ~500 messages per context)
  ├── tail-merge from /mobile/chat/history on cold open
  └── delta-apply from /mobile/chat/stream during live session

SshClient
  ├── Android Keystore-backed Ed25519 keypair (per-device)
  ├── known_hosts pinned to a0prod's host key (TOFU on first connect)
  ├── sshj-powered session with a pty channel for TerminalScreen
  └── no key export paths

ServiceState (singleton, in-memory StateFlow)
  ├── connectionState: StateFlow<ConnectionState>
  ├── pendingApprovals: StateFlow<List<Approval>>
  ├── lastContactAtMs: StateFlow<Long>
  ├── reconnectCount: StateFlow<Int>
  └── chatUnreadCount: StateFlow<Int>

MobileApiClient
  ├── HTTP calls to /mobile/* (namespace-agnostic buildUrl)
  └── ConnectivityManager.NetworkCallback for radio events
```

### The toggle

Main screen has a prominent on/off switch, same UX shape as the Tailscale Android app's VPN toggle.

- **Off (default):** SeekerZero is a regular app. Open it to see state. No background work, no notification, no battery cost.
- **On:** Foreground service starts. Persistent LOW-importance notification in the shade. Long-poll to `/mobile/approvals/stream` stays open. New approvals fire a HIGH-importance notification you can act on from the lock screen.

Toggle state persists to SharedPreferences. `BootReceiver` reads it on device boot; if the toggle was on, the service auto-starts after first unlock.

### Notifications

Two channels, created in `SeekerZeroApplication.onCreate` (copy the SeekerClaw `SeekerClawApplication.kt` pattern):

- **`seekerzero_service`** — `IMPORTANCE_LOW`, silent, `setShowBadge(false)`. The persistent "Connected to Agent Zero" notification while the service is running. Sits in the shade, doesn't annoy.
- **`seekerzero_approvals`** — `IMPORTANCE_HIGH`, default sound, `setShowBadge(true)`. For new approval gates. Notification payload carries the approval ID; inline action buttons for Approve and Reject on the notification itself, so the user can resolve from the lock screen without opening the app.

No other notifications exist. Routine A0 events never fire notifications. Discipline here is what keeps the phone quiet.

### Retry and reconnection policy

The long-poll to `/mobile/approvals/stream` runs on a phone over Tailscale. Mobile networks drop connections during Wi-Fi-to-cellular handoff, in elevators, in subways. The retry policy treats Android radio events as first-class input, not just HTTP errors.

**Error classification:**

| Error type | Action |
|---|---|
| `ECONNRESET`, `ETIMEDOUT`, `ECONNREFUSED`, `EPIPE`, `EAI_AGAIN`, `EHOSTUNREACH`, `ENETUNREACH`, `socket hang up` | Retry with backoff |
| HTTP 429 (rate limit) | Retry with backoff, honor `Retry-After` header |
| HTTP 500 / 502 / 503 / 504 | Retry with backoff |
| HTTP 200 with empty body (normal 60s long-poll timeout) | Reconnect immediately, no backoff counter increment |
| `ENOTFOUND` (DNS failure) | Fail fast — probably no network at all |
| HTTP 400 / 401 / 403 / 404 | Fail fast — client error, won't fix itself |

**Backoff schedule:** 1s → 2s → 4s, three attempts total, then mark offline.

**Android radio integration:** Subscribe to `ConnectivityManager.NetworkCallback` directly.
- `onLost`: immediately cancel the in-flight HTTP call, pause the retry loop, set connection state to `PAUSED_NO_NETWORK`. Do not burn battery hammering backoff when the radio knows the network is gone.
- `onAvailable`: resume the long-poll. Increment reconnect counter.
- Metered cellular (absent `NET_CAPABILITY_NOT_METERED`): keep the approvals long-poll running (one held connection is cheap) but skip background refreshes of the tasks list and any other non-essential pulls until the user pulls to refresh.

**Offline mark:** After three consecutive retry-exhausted reconnect attempts (~12s total), set `ServiceState.connectionState = OFFLINE`, fire one LOW-priority notification ("Lost connection to Agent Zero"), stop retrying until either a `ConnectivityManager` event says the network changed or the user hits the manual reconnect button (surfaced in Settings). Do not hammer forever.

### Offline-gap recovery

Every successful long-poll response (including empty 60s heartbeats) writes `lastContactAtMs` to SharedPreferences. On reconnect after any disconnection, the first call to `/mobile/approvals/stream` includes `?since=<lastContactAtMs>`.

**Reconnection notification rules:**
- If the gap was under 30 seconds → no notification, just resume silently.
- If the connection is flapping → require 30 seconds of stability before firing anything.
- If there were zero new approvals raised during the gap → no notification.
- Otherwise, fire one consolidated notification: *"Reconnected to Agent Zero (offline 12m). 3 approvals raised while offline. Tap to review."* Tapping opens the Approvals tab scrolled to the new items with visual flags.
- Nothing ever auto-approves. User decides.

**No-spam discipline:** One notification per meaningful reconnect. Not one per approval. Not one per minute the phone is offline.

### Watchdog timing

Borrowed from SeekerClaw's watchdog cadence, adapted for the long-poll model:

- Long-poll request timeout: 60 seconds (server holds open, returns empty on timeout)
- Reconnect backoff: 1s / 2s / 4s (three attempts)
- Offline declaration: after the 3-attempt backoff exhausts (~12s), or after 3 consecutive ConnectivityManager `onLost` events without recovery
- Stable-reconnect threshold for firing "back online" notifications: 30 seconds

---

## v1 surfaces

### Phase 1 — Approval gates ✅ shipped 2026-04-20

Ships when: an approval raised on a0prod fires a HIGH-priority notification on the Seeker, tap-approve from the lock screen (or the in-app card) completes the gate, server-side `/pending` drains. **Done.** Current state:
- `/mobile/approvals/{pending,stream,{id}/approve,{id}/reject}` wired, tailnet-peer-gated, atomic-write safe.
- Server-side approval source is a hand-edited JSON stub on a0prod — the real A0 approval-gate queue will be wired in a later pass (tracked separately; not blocking further phases).
- Client: real foreground service, long-poll, ConnectionWatchdog with network-callback short-circuit, BootReceiver, ApprovalsScreen with optimistic UI.
- Not yet wired: `GET /mobile/approvals/{id}` detail endpoint (cards are self-contained enough), notification inline action buttons (tap goes to app), multi-client dismissal broadcasts.

### Phase 5 — Chat

Ships when: typing a prompt on the phone gets the same A0 response you'd see on the web UI, with token-granular streaming, and cold-opening the tab shows recent messages within ~200ms from local cache.

**Server-side work:**
- Implement `/mobile/chat/history`, `/mobile/chat/stream`, `/mobile/chat/send`, `/mobile/chat/contexts`.
- Decide the contract between the mobile chat namespace and A0's existing chat context machinery. First pass: mobile chat lives in its own named context (e.g. `mobile-seekerzero`) so it doesn't entangle with web UI sessions. Future: let the phone pick a context.
- Token-stream shape: newline-delimited JSON events on the stream endpoint. Each event is `{type: "user_msg"|"delta"|"final", message_id, role, content|delta, created_at_ms}`.

**App work:**
- `ChatScreen`: scrolling message list (LazyColumn, reversed, infinite-scroll upward), input composer pinned to the bottom, send button, attach-approval-context affordance (v1.1 maybe). Assistant messages render streaming tokens with a subtle cursor.
- `ChatRepository` with Room-backed cache. Schema: `messages(context_id, id, role, content, created_at_ms, is_final)`. Cache keeps last 500 per context.
- `ChatViewModel`: observes `chatRepository.messagesFlow(contextId)` + sends via `MobileApiClient.chatSend`. Stream consumption happens in the service (background) or the VM (foreground), decided by whether the user has the tab visible.
- Foreground service runs `ChatLongPoll` only while there's either (a) the Chat tab visible or (b) an unfinished assistant response to collect. It does **not** run permanently — chat is active, not always-on.

**Out of Phase 5 (punt to 5.1 or later):** multi-context switching UI, file attachments, prompt templates, rich-text rendering beyond Markdown code blocks.

### Phase 6 — Terminal (SSH pty to a0prod)

Ships when: opening the Terminal tab on the phone connects to a0prod over SSH on the tailnet within ~1 second of first tap (after setup), and you can run `htop` / `vim` / `tmux attach` interactively.

**Server-side work:**
- None in A0. Just standard OpenSSH on a0prod host.
- Operator task: drop SeekerZero's per-device public key (displayed on SetupScreen) into `~/.ssh/authorized_keys` for your host user once per device. Documented in `server/README.md`.

**App work:**
- `SshKeyManager`: on first SetupScreen load after QR scan, generate an Ed25519 keypair in Android Keystore (hardware-backed where available; fall back to software-backed on older Seeker models). Private key never leaves the phone. Surface the public key as a copyable block + QR code on SetupScreen for easy desktop paste.
- `SshClient`: sshj or a lightweight Kotlin SSH library. One session per visible TerminalScreen. pty channel with terminal type `xterm-256color`, reasonable initial size, resize-on-rotation.
- `TerminalScreen`: Compose wrapper around a terminal emulator widget (options: `AndroidTerminalView` from Termux as a dependency, or a minimal Compose-native renderer; pick during scaffolding). IME input, hardware-keyboard support. Back-gesture exits cleanly and disconnects.
- `known_hosts` pinned to a0prod's host key on first successful connect (TOFU). Mismatch on reconnect blocks and shows an explicit warning.
- Biometric gate (BiometricPrompt) on tab open — the terminal is the one surface where "phone in attacker's hand" must not mean "shell on a0prod." Fingerprint or face unlock required before the pty opens. Session is cached for ~5 minutes after successful unlock so tab-switching during active use doesn't re-prompt.
- Kill-switch: a visible "Disconnect" button in the TerminalScreen top bar.

**Security posture:** the terminal tab intentionally widens the blast radius of a lost phone. The biometric gate + screen lock + per-device key (revocable server-side by removing the authorized_keys line) is the layered defense. Remediation on loss: remove device from Tailscale admin + remove the authorized_keys line.

### Phase 7 — Scheduled tasks (read + create)

Ships when: the phone lists all scheduled tasks with live status, lets the user create a new scheduled task from a form, and the new task shows up in `task_scheduler` and runs on its cron.

**Server-side work:**
- `GET /mobile/tasks/scheduled` — list with last-run + next-run + state
- `GET /mobile/tasks/{id}` — detail (cron, prompt, profile, recent run history)
- `POST /mobile/tasks` — create. Body validated: cron parses, profile exists, prompt non-empty
- `POST /mobile/tasks/{id}/run` — ad-hoc run
- `POST /mobile/tasks/{id}/enable` + `/disable` — state toggle without editing

**App work:**
- `TasksScreen`: list of tasks with next-run countdown + state pill + enable/disable toggle + "Run now" button per item.
- `TaskComposerScreen`: full-screen form. Fields: name, prompt (multiline), cron (with a "common patterns" picker: hourly / daily at / weekly on / custom), profile (dropdown populated from A0), initial state (enabled / disabled).
- Navigation: Tasks tab has a FAB / top-bar "+ New task" → opens composer → submit → returns to list with the new row highlighted briefly.

**Out of Phase 7 (editing existing tasks):** stays v2. The common case is create + enable/disable + run-now; mutating an existing cron or prompt is rare and the web UI covers it.

---

## Shared UI component library (build before any screen)

Before a single screen is built, scaffold these components in `app/src/main/java/dev/seekerzero/app/ui/components/`. Screens may only use these components plus Material 3 primitives — no inline duplicates. This is the step SeekerClaw skipped, and their `UI_COMPONENT_AUDIT.md` is a detailed map of the technical debt they accumulated as a result. We pay it upfront for free.

**Components:**

1. **`SeekerZeroTopAppBar(title, onBack)`** — Material 3 TopAppBar with a single back arrow. Every detail screen uses this.
2. **`SeekerZeroScaffold(title, onBack, content)`** — Scaffold wrapping the TopAppBar + background color + inner padding. Every non-tab screen uses this.
3. **`SectionLabel(title, action?)`** — all-caps section header. One font, one size, one color, one letter-spacing. Optional trailing action slot for "Clear" / "Refresh" buttons.
4. **`ConfigField(label, value, onChange, trailingHelp?)`** — labeled text field with optional help "i" button.
5. **`InfoDialog(title, body, onDismiss)`** — single `AlertDialog` wrapper for help text. Paired with ConfigField and used anywhere help is needed.
6. **`CardSurface(modifier, content)`** — enforces background color and corner shape only. **Does not internalize padding.** Callers pass their own padding via modifier. Hard rule from SeekerClaw's audit after they found 31 inconsistent instances. No `padding` parameter on this component, ever.
7. **`InfoRow(label, value, dotColor?, isLast?)`** — one-line key/value row with optional leading status dot and trailing divider.
8. **`SettingRow(label, checked, onChange, info?)`** — label + toggle switch, with optional info tooltip. Uses the standardized Switch colors from the theme.
9. **`StatusDot(color, size = 10.dp, pulsing = false)`** — circular indicator. Standard size 10dp. Optional pulsing animation for the connection-live indicator on the main screen.

**Theme invariants:**
- One `SeekerZeroSwitchColors` pair for all toggles. No per-screen overrides.
- One `SeekerZeroColors.Divider` for all dividers. No per-screen overrides.
- Dark theme only in v1.
- Material 3 color tokens only; no raw hex in screens.

SeekerClaw hit a Switch-color bug where LogsScreen used `Primary` (red) and SettingsScreen used `ActionPrimary` (green) for semantically identical toggles. We avoid this by routing every toggle through `SettingRow`, which always uses `SeekerZeroSwitchColors`.

---

## Build configuration

Derived from SeekerClaw's `build.gradle.kts` with aggressive subtractions.

**Copy:**
- `compileSdk = 35`, `minSdk = 34`, `targetSdk = 35`
- Java 17 source/target, Kotlin jvmTarget = "17"
- `buildFeatures { compose = true; buildConfig = true }`
- Plugin aliases: `android.application`, `kotlin.android`, `kotlin.compose`, `kotlin.serialization`
- Compose BOM, `material3`, `material.icons.extended`, `navigation.compose`, `lifecycle.*.compose`, `activity.compose`
- `kotlinx.serialization.json`
- `androidx.browser:browser:1.8.0` (Custom Tabs, in case we want to deep-link to Tailscale settings)
- CameraX (`camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view`) at version 1.4.1
- `com.journeyapps:zxing-android-embedded:4.3.0` for QR scanning (includes CameraX integration, no Google Play Services)
- ProGuard/R8 minify + shrinkResources on release
- `BuildConfig` fields injected at build time: `versionName`, `versionCode`, git SHA (short), build date (ISO). Surfaced in the Settings screen's "About" section.

**Delete:**
- NDK / CMake / `externalNativeBuild` (no native code)
- `libnode/bin/` jniLibs src dir (no nodejs-mobile)
- The ~60-line `DownloadNodejsTask` Gradle custom task (no nodejs-mobile)
- `abiFilters.addAll(listOf("arm64-v8a"))` (no native libs)
- Product flavors (`dappStore` / `googlePlay`) — single flavor, sideload only
- Firebase BoM, Firebase Analytics, `google-services.json` handling
- `com.solanamobile:mobile-wallet-adapter-clientlib-ktx` + `org.sol4k:sol4k`
- `nanohttpd` (no local HTTP bridge — nothing to serve locally)
- Signing config dance for two flavors — single debug-signed keystore for sideload is fine

Expected size: SeekerClaw's `build.gradle.kts` is ~230 lines. SeekerZero's target is ~80 lines.

**Custom `BuildConfig` fields to inject:**
- `A0PROD_HOST` — default tailnet host string for debug builds (`a0prod.<your-tailnet>.ts.net`)
- Git SHA (short), via `providers.exec` at build time
- Build date (ISO format)

---

## Manifest

Required permissions:

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.CAMERA" />  <!-- QR scanning only -->
```

**Not needed** (explicitly rejected): `SEND_SMS`, `CALL_PHONE`, `READ_CONTACTS`, `WRITE_CONTACTS`, `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`. These are for SeekerClaw's on-device agent reaching into the phone. SeekerZero has no agent on the phone. Revisit only if v2 device-as-capability ever ships.

Application setup:
- `android:allowBackup="false"` (no ADB backup)
- `SeekerZeroApplication` as the application class

Service declaration:
```xml
<service
    android:name=".service.SeekerZeroService"
    android:foregroundServiceType="specialUse"
    android:exported="false" />
```

**No `android:process=":node"`** — service runs in the main app process.

Other activities:
- `MainActivity` (launcher)
- `QrScannerActivity` (portrait, `excludeFromRecents="true"`) — isolated QR scanning

`BootReceiver` with an intent filter for `android.intent.action.BOOT_COMPLETED`, exported=false.

---

## File system layout on device

```
/data/data/dev.seekerzero.app/
├── files/
│   ├── logs/                # LogCollector output, rotated at 5MB
│   └── cache/               # Cached approval details, task detail, etc.
├── databases/
│   └── chat.db              # Room: messages(context_id, id, role, content, ts, is_final)
│                            # + known_hosts(host, fingerprint) for SSH TOFU
└── shared_prefs/
    └── seekerzero_prefs.xml # Config + toggle state + lastContactAtMs + last-seen-chat-id
```

**Secrets:** the SSH private key lives in **Android Keystore**, not the filesystem, addressed by alias. It never appears in `files/`, `databases/`, or `shared_prefs/`. Claude/OpenAI API keys are never stored — the phone does not call those APIs.

Everything in `files/` and `databases/` is cache that can be rebuilt from `/mobile/*` or from a fresh Keystore keygen.

---

## Project structure

```
seekerzero/
├── app/
│   ├── src/main/
│   │   ├── java/dev/seekerzero/app/
│   │   │   ├── MainActivity.kt                  # Trivial Compose entry point
│   │   │   ├── SeekerZeroApplication.kt         # Two notification channels
│   │   │   ├── ui/
│   │   │   │   ├── theme/                       # Dark theme, Material 3, locked color tokens
│   │   │   │   ├── components/                  # Shared UI component library (9 components)
│   │   │   │   ├── navigation/NavGraph.kt       # Setup → Main (4 tabs)
│   │   │   │   ├── setup/SetupScreen.kt         # QR scan → SSH pubkey enrollment → permissions
│   │   │   │   ├── approvals/                   # Phase 1 (shipped)
│   │   │   │   ├── chat/                        # Phase 5: ChatScreen + ChatViewModel + composer
│   │   │   │   ├── tasks/                       # Phase 7: list + composer
│   │   │   │   ├── terminal/                    # Phase 6: TerminalScreen + emulator widget
│   │   │   │   └── settings/
│   │   │   │       ├── SettingsScreen.kt
│   │   │   │       └── SettingsHelpTexts.kt     # Mirrors docs/internal/SETTINGS_INFO.md
│   │   │   ├── service/
│   │   │   │   ├── SeekerZeroService.kt         # Foreground service, specialUse, approvals long-poll
│   │   │   │   └── ConnectionWatchdog.kt        # Retry/reconnection policy
│   │   │   ├── api/
│   │   │   │   ├── MobileApiClient.kt           # OkHttp client for /mobile/*
│   │   │   │   └── models/                      # @Serializable data classes
│   │   │   ├── chat/
│   │   │   │   ├── ChatRepository.kt            # Room-backed cache + tail-merge
│   │   │   │   └── ChatDatabase.kt              # Room schema
│   │   │   ├── ssh/
│   │   │   │   ├── SshKeyManager.kt             # Keystore-backed Ed25519
│   │   │   │   ├── SshClient.kt                 # sshj wrapper, pty channel
│   │   │   │   └── KnownHostsStore.kt           # TOFU pinning of a0prod host key
│   │   │   ├── receiver/
│   │   │   │   └── BootReceiver.kt              # Restore toggle state on reboot
│   │   │   ├── config/
│   │   │   │   ├── ConfigManager.kt             # SharedPreferences (no keys here)
│   │   │   │   └── QrParser.kt                  # Plain JSON parse
│   │   │   ├── qr/
│   │   │   │   └── QrScannerActivity.kt         # Portrait, excludeFromRecents
│   │   │   └── util/
│   │   │       ├── LogCollector.kt              # In-memory ring buffer + local file
│   │   │       └── ServiceState.kt              # Pure StateFlow
│   │   ├── res/
│   │   │   └── values/strings.xml               # Mirrors docs/internal/TEMPLATES.md
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts                         # ~80 lines, derived from SeekerClaw
├── build.gradle.kts                             # Root
├── settings.gradle.kts
├── gradle/libs.versions.toml
├── CLAUDE.md                                    # Conventions for Claude Code sessions
├── PROJECT.md                                   # Living state of the project
├── NOTICES                                      # SeekerClaw MIT attribution
├── docs/internal/
│   ├── SETTINGS_INFO.md                         # Mirrors SettingsHelpTexts.kt
│   └── TEMPLATES.md                             # Mirrors strings.xml
└── README.md                                    # Minimal, personal tool
```

---

## QR config payload

Plain JSON, no encryption (no secrets — Tailscale identity is the auth):

```json
{
  "v": 1,
  "a0_host": "a0prod.<your-tailnet>.ts.net",
  "port": 50080,
  "mobile_api_base": "/mobile",
  "client_id": "seekerzero-james-seeker",
  "display_name": "James's Seeker"
}
```

Base64url-encoded and wrapped in a `seekerzero://config?payload=<base64>` URI scheme (so scanning from outside the app via a system QR scanner deep-links into SetupScreen). Stored in SharedPreferences on first scan.

Generated by `/mobile/config/qr` endpoint on a0prod. User opens the URL in a browser on any device, scans the displayed QR from the Seeker.

---

## First-run flow

1. User sideloads the APK.
2. Opens the app → sees welcome screen.
3. Taps "Scan QR" → requests `CAMERA` permission → opens `QrScannerActivity`.
4. Points at the QR from `/mobile/config/qr` (or the `tools/generate_qr.py` PNG).
5. App parses the payload, stores to SharedPreferences.
6. App calls `GET /mobile/health` to confirm reachability.
7. **SSH enrollment step:** app generates an Ed25519 keypair in Android Keystore (alias `seekerzero_ssh_v1`) if one doesn't already exist. Shows the public key as: (a) a copyable text block with a big "Copy" button, and (b) a smaller QR encoding the same line, for the rare case the user wants to SSH-paste via their desktop. Instructs: *"SSH into a0prod once and append this line to ~/.ssh/authorized_keys. Tap Continue when done."*
8. App requests notification permission (API 33+).
9. Shows battery optimization exemption dialog → opens Android settings to grant it.
10. Lands on the main screen with the service toggle off. User flips it on to go live.

If any step fails (QR parse error, health check fail, SSH test-connect fail, permission denied), show a clear error message with a retry button. Manual entry fallback for the tailnet host is available but hidden behind a "Can't scan?" link.

**Test-connect after SSH enrollment (optional):** on "Continue", the app may attempt a no-op SSH handshake to a0prod. If it succeeds, the Terminal tab is unlocked; if it fails (authorized_keys not updated yet), show a clear "Can't reach a0prod over SSH — check authorized_keys and retry" banner without blocking the rest of the app. Terminal tab then shows the same banner until SSH works.

---

## Repo conventions

### CLAUDE.md / PROJECT.md split

Adopted from SeekerClaw. Two separate files at repo root, both read by Claude Code every session.

- **`CLAUDE.md`** — how to work on the project. Project description, tech stack, architecture overview, file system layout, coding conventions, pitfalls, what NOT to build, build commands, the `/mobile/*` API contract. Updated only when conventions change.
- **`PROJECT.md`** — living state of the project. Current version, what's shipped, what's in progress, what's planned, known limitations, changelog. Updated after every feature.

### SETTINGS_INFO.md mirror

`docs/internal/SETTINGS_INFO.md` is a markdown table documenting every tooltip, status text, and Settings info string. It mirrors `ui/settings/SettingsHelpTexts.kt` line-for-line. Update markdown first for review, then sync to Kotlin. The app reads only from the Kotlin constants; the markdown is a human-readable review surface.

### TEMPLATES.md mirror

`docs/internal/TEMPLATES.md` is the single source of truth for user-facing strings:
- Foreground service notification text
- Approval notification titles and action button labels
- Setup flow copy (welcome, QR prompt, success, error states)
- Empty states per tab ("No pending approvals", "No scheduled tasks", etc.)
- Connection-state red summaries ("A0 unreachable", "SSH to a0prod refused", etc.) wherever they surface

Mirrors Android `res/values/strings.xml`. Update markdown first, sync to XML. Gives lint coverage from strings.xml and free translation support if we ever add i18n.

### Notification tone decision

The foreground notification is the single most-seen string in the whole app. SeekerClaw's is warm and personified: *"SeekerClaw · Your companion is awake 🟢"*. SeekerZero's should be flatter and utilitarian: *"SeekerZero · Connected to Agent Zero"*. No emoji, no personality. This matches the tool's identity as a command surface, not a companion. Locked.

---

## Security considerations

SeekerZero v3 intentionally widens the blast radius relative to v2 (terminal access, task creation, chat). The defense-in-depth layout:

**Layer 1 — Tailscale network identity.** `/mobile/*` endpoints on a0prod verify the request comes from a 100.x tailnet peer before accepting. SSH (port 22 on a0prod) is reachable only over the tailnet — a0prod's UFW/iptables policy already restricts port 22 to the tailnet interface.

**Layer 2 — Per-device SSH key in Android Keystore.** The private key is generated on-device, hardware-backed where the Seeker's StrongBox allows, and never leaves the phone. No backup, no export. The public key is dropped into a0prod's `~/.ssh/authorized_keys` manually during setup. Revocation on device loss: remove that one line from authorized_keys (in addition to removing the device from Tailscale admin).

**Layer 3 — Android screen lock.** The phone's own lock screen is the primary gate against "lost phone in someone else's hand." Apps inside an unlocked phone can access /mobile/* freely; this is fine because the screen lock is the assumed barrier.

**Layer 4 — Biometric prompt on Terminal tab.** Even with the phone unlocked, opening the Terminal tab requires BiometricPrompt (fingerprint or face). This bounds the worst case where the phone is handed to someone briefly. Cached for ~5 minutes of active terminal use to avoid re-prompting during tab-switching mid-session.

**Layer 5 — Audit trail on a0prod.** All `/mobile/*` actions tag origin `seekerzero` with the device's `client_id`. SSH logins are captured by standard OpenSSH logging. The `audit.log` visible from Diagnostics (v2) surfaces both streams.

**What SeekerZero still does NOT store:** Claude/OpenAI/Gemini API keys; plaintext A0 `secrets.env` contents; Telegram bot tokens; Solana wallet material; anything that would let an attacker *originate* actions against external services without already being inside A0. The phone is a control surface, not a keyring.

**Anti-features (explicitly absent):**
- No `android:allowBackup` (`="false"` in manifest). ADB backup cannot exfiltrate.
- No cloud sync of any kind.
- No telemetry, no crash reporting, no analytics. Nothing about the user's A0 activity leaves the tailnet.
- No remote kill-switch for the app (beyond Tailscale device revocation). Intentional — a remote kill channel is itself a supply-chain risk.

---

## Out of scope for v1

- Cost dashboard (the old Phase 3) — cut from v3. Revisit as a v2 widget if it earns its way back.
- Dedicated Diagnostics tab (the old Phase 4) — cut. Its data (connection state, reconnect counter, build SHA, last-contact time) surfaces inline on relevant tabs or in Settings; a standalone tab is not load-bearing.
- Editing existing scheduled tasks (create yes, edit no). Web UI covers the rare edit case.
- Any on-device AI inference. No local models, no embeddings, no reasoning on the phone.
- Solana Mobile Stack integration of any kind.
- Solana wallet, blockchain interaction, x402, agent payments.
- Device-as-capability (`/device/*` inverse API exposing GPS, camera, SMS, clipboard back to A0) — v2, but `/mobile/*` and the foreground service are designed to accommodate a sibling `/device/*` namespace without restructuring.
- iOS, desktop, Wear OS, tablet-specific layouts.
- Firebase, Crashlytics, telemetry of any kind.
- Play Store or dApp Store distribution.
- Bulk action tiles on the Approvals tab (swipe-triage).
- Per-byte cellular bandwidth accounting.
- Auto-execution of anything missed while offline (user still decides).
- Multi-language support.
- Light theme.
- Home-screen widgets.
- Merging with the benched Seeker dapp project.
- Password-authenticated SSH (key-only; no agent forwarding either).
- Paste of arbitrary SSH keys from other devices into the app (keygen only happens in-app; keys are per-device).

---

## Known cleanups (deferred from shipped phases)

Surfaces that work but have rough edges worth revisiting:

- **Drawer hides left-side tabs** (Phase 5 cleanup, 2026-04-21): drawer hoisted to `MainScaffold` so the scrim covers the bottom nav (previously it didn't — fixed). But the drawer sheet is opaque and covers the left ~300 dp of the screen, which on a typical phone hides the Chat and Approvals tab icons. Only Tasks (rightmost tab) stays visible under the translucent scrim. User must tap the scrim to close the drawer before switching to a hidden tab. Options: narrower drawer sheet (e.g. 240 dp), or swap the side drawer for a full-screen chat-picker screen. Deferred — not blocking.

- **Flask chat-stream thread starvation, recurring** (Phase 5 / 5.1, observed 2026-04-21 twice): `/mobile/chat/stream` holds one request thread per active subscriber, and Flask's thread pool under Uvicorn-WSGI is finite (we don't know the exact number but it's low single-digit). Aggressive client reconnects / context switches pile subscribers faster than the keepalive-detected disconnect releases them, and once all threads are held, `/mobile/health`, `/mobile/chat/contexts`, and `/mobile/approvals/stream` stop responding. Current mitigations (single-subscriber-per-context eviction + 5 s keepalive + socket-close detection in the generator) were insufficient under Phase 5.1 multi-chat testing. Next fixes to consider: (a) explicit max-lifetime on stream generators (e.g. force-return after 60 s, client reconnects cleanly), (b) watchdog thread that reaps pub/sub subscribers older than N seconds, (c) bump Uvicorn's WSGI thread pool, (d) longer term: move chat streaming to a separate async-native framework (ASGI/Starlette) that doesn't use a bounded thread pool.
- **Cost / Diagnostics tab stubs** still render as empty screens in `MainScaffold` even though they were cut from v3. Should be removed from `TABS` in `ui/main/MainScaffold.kt` and their `Stub` composables deleted. Trivially one commit.
- **`_05_task_stats_display` footer on mobile finals** (Phase 5 Step 4a): A0's cost/timing table lands in every assistant message on the phone. Fine as data; visually heavy for a phone UI. If we want to strip it, a targeted pass in `_99_seekerzero_final.py`'s `_extract_final_text` can trim off everything after the first `---` horizontal rule (that's where the footer begins).

---

## v2 directions (architecture-affecting, worth knowing now)

### Device-as-capability inversion

SeekerClaw's "Android Bridge" exposes phone capabilities (GPS, camera, SMS, clipboard, TTS) to their on-device agent. The inverted version for SeekerZero: SeekerZero exposes a `/device/*` callback API that a0prod hits *through the tailnet* to get phone capabilities.

- A0 needs James's location for a task → calls SeekerZero → returns GPS
- A0 needs vision analysis → asks SeekerZero → phone captures a photo and uploads
- A0 wants to read or write clipboard → SeekerZero is the bridge

This makes SeekerZero a two-way client: UI inbound (you → A0), capabilities outbound (A0 → phone). Same tailnet, same auth, same foreground service (already running when toggled on). Design `/mobile/*` and the foreground service so a sibling `/device/*` namespace can be added without restructuring. In practice this means the `MobileApiClient` and `SeekerZeroService` are both namespace-agnostic — they don't hardcode `/mobile` as the only API path.

### Edit existing scheduled tasks

v1 ships create + enable/disable + run-now. Editing cron/prompt/profile is v2.

### Bulk action tiles on Approvals

When 5+ approvals from the same source are pending, show a "Review all Gmail replies" tile that opens a focused queue view — swipe-right approve, swipe-left reject, undo bar. Same UX shape as email triage apps. v2 or v3.

### File attachments in Chat

Send an image or short audio clip from the phone into the Chat context. Requires deciding how A0 stores and retrieves attachments — non-trivial. v2.

### Multi-context chat

v1 pins mobile chat to a single `mobile-seekerzero` context. v2 lets the phone pick among A0's active contexts (or start a new one), giving the web UI and the phone parity on context management.

---

## Bottom line

SeekerZero v3 is a full Agent Zero client on the Solana Seeker: chat (token-streamed), approvals (end-to-end shipped), scheduled tasks (read + create), and a terminal (SSH pty to a0prod). Everything flows over the existing Tailscale tailnet; the tailnet is the transport auth layer and SSH's per-device Keystore-backed Ed25519 key is the terminal-tier identity. No on-device AI, no Node runtime, no subprocess, no wallet, no Solana Mobile Stack, no Play Store, no telemetry. Minimal persistent state — Room DB for chat cache, SharedPreferences for config, Android Keystore for the one secret that does live on the phone. Claude/OpenAI API keys are never stored; the phone never calls those APIs.

**Build order from here:**
1. Phase R (this redraft) ✅
2. Phase 5 — Chat (server-side `/mobile/chat/*`, ChatScreen, Room cache)
3. Phase 6 — Terminal (SSH keygen + enrollment flow, TerminalScreen, biometric gate)
4. Phase 7 — Tasks read+write (server-side POST endpoints, TaskComposerScreen)

Later, not in v1: real approval-gate wiring on a0prod (replace the stub file), `/mobile/approvals/{id}` detail endpoint, `/mobile/config/qr` server-side renderer, notification inline action buttons, multi-client dismissal broadcasts.
