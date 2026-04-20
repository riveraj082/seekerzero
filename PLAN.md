# SeekerZero — Plan v2 (2026-04-09)

**Supersedes:** `seekerzero-plan-2026-04-09.md`, `seekerzero-vs-seekerclaw-2026-04-09.md`, `seekerzero-vs-seekerclaw-code-review-2026-04-09.md`, `seekerzero-network-recovery-2026-04-09.md`, `seekerzero-ui-components-2026-04-09.md`.

Native Android app on the Solana Seeker phone. Mobile command surface for Agent Zero (a0prod). Separate codebase from the benched Seeker dapp project; same device, different job. The Seeker is treated as a normal Android phone — no Solana Mobile Stack, no wallet, no blockchain interaction.

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
| **Transport** | Tailscale. Seeker joins existing tailnet, talks directly to a0prod at `a0prod.your-tailnet.ts.net`. No relay, no separate WireGuard layer. |
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
| **v1 surfaces** | (1) Approval gates, (2) Scheduled task visibility, (3) Cost dashboard, (4) Diagnostics. |
| **Sequencing** | Approvals → tasks → cost → diagnostics. Each independently shippable. |
| **Repo conventions** | `CLAUDE.md` + `PROJECT.md` split. `docs/internal/SETTINGS_INFO.md` mirroring `SettingsHelpTexts.kt`. `docs/internal/TEMPLATES.md` mirroring Android `strings.xml`. |

---

## Server-side: the `/mobile/*` API namespace on a0prod

New namespace, JSON contract, lives alongside the existing web UI routes. SeekerZero is the first consumer; the namespace is designed to outlive it.

### Identity and registration

Register `seekerzero` as a client type in Agent Zero's client registry alongside `webui` and `telegram`. The approval-gate routing table learns to dispatch to SeekerZero clients. Audit logs tag origin = `seekerzero` for any action taken via the mobile API.

### Multi-client approval coexistence

Approval gates broadcast to all registered clients (Telegram + SeekerZero + webui). First resolution wins. Other clients get a "resolved elsewhere" dismissal push and silently update their local state. Two phones buzzing for the same approval is fine; two channels racing to resolve it is not. The server records which client resolved each gate in the audit trail.

### Endpoints (v1)

**Approvals**
- `GET /mobile/approvals/pending` — list open approval gates
- `GET /mobile/approvals/stream?since=<ms>` — long-poll endpoint, 60s server-held hold. When `since` is provided (on reconnect), returns everything raised during the gap first (capped at 24h lookback) then falls into normal long-poll behavior. Without `since`, behaves as normal stream.
- `POST /mobile/approvals/{id}/approve` — approve a gate
- `POST /mobile/approvals/{id}/reject` — reject a gate
- `GET /mobile/approvals/{id}` — detail for one gate

**Tasks (Phase 2, read-only)**
- `GET /mobile/tasks/scheduled` — list scheduled tasks with last-run status and next-run time

**Cost (Phase 3, read-only)**
- `GET /mobile/cost/summary` — daily / 7-day / 30-day rollups from the existing 4-type cost tracking (input, output, cache write, cache read)

**Diagnostics (Phase 4)**
- `GET /mobile/health` — on-demand health snapshot: subordinate fleet status (a0-think, a0-work, a0-quick, a0-embed — up/down + last response time), scheduler health, cost posture, and a natural-language "what's wrong" string populated by Agent Zero when anything is degraded

**Config handoff**
- `GET /mobile/config/qr` — renders a scannable QR (PNG or data URI) encoding the SeekerZero config payload. Accessed from a browser on a0prod during first-run setup.

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
  ├── SetupScreen (first-run only)
  └── MainScaffold (4 bottom tabs)
      ├── ApprovalsScreen
      ├── TasksScreen (Phase 2)
      ├── CostScreen (Phase 3)
      └── DiagnosticsScreen (Phase 4)

SeekerZeroService (foreground, specialUse)
  ├── LongPollClient → /mobile/approvals/stream
  ├── ConnectionWatchdog → retry/reconnection policy
  └── NotificationManager → two channels

ServiceState (singleton, in-memory StateFlow)
  ├── connectionState: StateFlow<ConnectionState>
  ├── pendingApprovals: StateFlow<List<Approval>>
  ├── lastContactAtMs: StateFlow<Long>
  └── reconnectCount: StateFlow<Int>

MobileApiClient
  ├── HTTP calls to /mobile/*
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
- Metered cellular (absent `NET_CAPABILITY_NOT_METERED`): keep the long-poll running (one held connection is cheap) but skip background refreshes of the tasks, cost, and diagnostics tabs until the user pulls to refresh.

**Offline mark:** After three consecutive retry-exhausted reconnect attempts (~12s total), mark tailnet as offline in Diagnostics, fire one LOW-priority notification ("Lost connection to Agent Zero"), stop retrying until either a `ConnectivityManager` event says the network changed or the user hits the manual reconnect button. Do not hammer forever.

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

### Phase 1 — Approval gates (highest leverage)

Ships when: a Gmail-send approval raised on a0prod fires a HIGH-priority notification on the Seeker, tap-approve from the lock screen completes the gate, web UI sees it resolved in real time.

**Server-side work:**
- Register `seekerzero` as a client type in the approval routing table
- Implement `/mobile/approvals/pending`, `/mobile/approvals/stream`, `/mobile/approvals/{id}/approve`, `/mobile/approvals/{id}/reject`, `/mobile/approvals/{id}`
- Implement `/mobile/config/qr` for first-run setup
- Implement the multi-client coexistence rule (first resolution wins, broadcast dismissals)

**App work:**
- Scaffold the repo, project structure, build.gradle.kts
- Create the nine shared UI components (below) before any screen is built
- `SetupScreen`: camera permission → QR scan → parse tailnet config → store in SharedPreferences → ping `/mobile/health` to confirm reachability → request notification permission (API 33+) → battery optimization exemption dialog → done
- `ApprovalsScreen`: list of pending approvals, detail view, approve/reject actions
- `SeekerZeroService`: foreground service with `specialUse` type, long-poll loop, retry/reconnection policy, ConnectivityManager integration
- Notification pipeline with two channels, inline action buttons for approve/reject
- `BootReceiver` restoring toggle state

### Phase 2 — Scheduled task visibility

Read-only. The tab shows scheduled tasks with last-run status and next-run time. No editing, no manual triggering.

**Server-side:** `/mobile/tasks/scheduled` endpoint reading from Agent Zero's existing `task_scheduler` state.

**App:** `TasksScreen` with a simple list using the shared components.

Ships when: scheduled task list on the phone matches `task_scheduler` state on a0prod.

### Phase 3 — Cost dashboard

Read-only. Numbers in v1, visualizations in v2.

**Server-side:** `/mobile/cost/summary` endpoint reading from the existing 4-type cost tracking.

**App:** `CostScreen` showing today / 7-day / 30-day rollups, broken down by the four cost types (input, output, cache write, cache read).

Ships when: numbers on the phone match the cost tracking source of truth on a0prod.

### Phase 4 — Diagnostics

The screen that makes SeekerZero feel like a real command center. All data exists server-side already; this just exposes it.

**Server-side:** `/mobile/health` endpoint returning:
- Subordinate fleet status (a0-think, a0-work, a0-quick, a0-embed — each up/down with last response time)
- Scheduler health (last run of each scheduled task, error states)
- Cost posture (today's spend vs typical)
- "What's wrong right now" natural-language summary from Agent Zero when any subsystem is red

**App:** `DiagnosticsScreen` showing:
- Connection state (connected / paused-no-network / reconnecting / offline)
- `lastContactAtMs` as human-readable "last seen Agent Zero X seconds ago"
- Reconnect counter (session and lifetime)
- Manual reconnect button (forces long-poll drop and retry)
- Build identity: versionName + git SHA + build date from `BuildConfig`
- a0prod reachability + last successful `/mobile/health` response time
- Subordinate fleet status table
- Scheduler health summary
- Cost posture summary
- "What's wrong right now" string when anything is degraded
- Toggle state mirror (so you can see connection toggle from this tab)

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
- `BuildConfig` fields injected at build time: `versionName`, `versionCode`, git SHA (short), build date (ISO). The Diagnostics tab reads these directly.

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
- `A0PROD_HOST` — default tailnet host string for debug builds (`a0prod.your-tailnet.ts.net`)
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
│   └── cache/               # Cached approval details, cost data, etc.
├── shared_prefs/
│   └── seekerzero_prefs.xml # Config + toggle state + lastContactAtMs
└── (no databases, no workspace, no skills)
```

Deliberately minimal. No SQLite, no databases, no workspace directory. Everything beyond SharedPreferences is cache that can be rebuilt from `/mobile/*` at any time.

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
│   │   │   │   ├── setup/SetupScreen.kt         # QR scan, manual entry, permissions flow
│   │   │   │   ├── approvals/ApprovalsScreen.kt
│   │   │   │   ├── tasks/TasksScreen.kt         # Phase 2
│   │   │   │   ├── cost/CostScreen.kt           # Phase 3
│   │   │   │   ├── diagnostics/DiagnosticsScreen.kt  # Phase 4
│   │   │   │   └── settings/
│   │   │   │       ├── SettingsScreen.kt
│   │   │   │       └── SettingsHelpTexts.kt     # Mirrors docs/internal/SETTINGS_INFO.md
│   │   │   ├── service/
│   │   │   │   ├── SeekerZeroService.kt         # Foreground service, specialUse, long-poll loop
│   │   │   │   └── ConnectionWatchdog.kt        # Retry/reconnection policy
│   │   │   ├── api/
│   │   │   │   ├── MobileApiClient.kt           # Ktor or OkHttp client for /mobile/*
│   │   │   │   ├── LongPollClient.kt            # /mobile/approvals/stream with since param
│   │   │   │   └── models/                      # @Serializable data classes
│   │   │   ├── receiver/
│   │   │   │   └── BootReceiver.kt              # Restore toggle state on reboot
│   │   │   ├── config/
│   │   │   │   ├── ConfigManager.kt             # SharedPreferences, no Keystore
│   │   │   │   └── QrParser.kt                  # Plain JSON parse, no decryption
│   │   │   ├── qr/
│   │   │   │   └── QrScannerActivity.kt         # Portrait, excludeFromRecents
│   │   │   └── util/
│   │   │       ├── LogCollector.kt              # In-memory ring buffer + local file
│   │   │       └── ServiceState.kt              # ~20 lines, pure StateFlow
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
  "a0_host": "a0prod.your-tailnet.ts.net",
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
4. Points at the QR on a0prod's `/mobile/config/qr` page.
5. App parses the payload, stores to SharedPreferences.
6. App calls `GET /mobile/health` to confirm reachability.
7. On success: requests notification permission (API 33+).
8. Shows battery optimization exemption dialog → opens Android settings to grant it.
9. Lands on the main screen with the toggle off. User flips it on to go live.

If any step fails (QR parse error, health check fail, permission denied), show a clear error message with a retry button. Manual entry fallback for the tailnet host is available but hidden behind a "Can't scan?" link.

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
- Diagnostics red-state summaries ("A0 unreachable", "Subordinate a0-think down", etc.)

Mirrors Android `res/values/strings.xml`. Update markdown first, sync to XML. Gives lint coverage from strings.xml and free translation support if we ever add i18n.

### Notification tone decision

The foreground notification is the single most-seen string in the whole app. SeekerClaw's is warm and personified: *"SeekerClaw · Your companion is awake 🟢"*. SeekerZero's should be flatter and utilitarian: *"SeekerZero · Connected to Agent Zero"*. No emoji, no personality. This matches the tool's identity as a command surface, not a companion. Locked.

---

## Security considerations

- No API keys stored in the app. No Android Keystore usage. No AES. The Tailscale layer is the entire auth story.
- `android:allowBackup="false"` prevents ADB backup from exfiltrating the tailnet config.
- `/mobile/*` endpoints on a0prod verify the request comes from a tailnet peer at the network layer before accepting.
- If the Seeker is lost or compromised, the remediation is: remove the device from the Tailscale admin panel. All `/mobile/*` calls from that device immediately fail auth. No credential rotation, no key revocation, no dance.
- No telemetry, no crash reporting, no analytics. Nothing about the user's A0 activity leaves the tailnet.

---

## Out of scope for v1

- Sending prompts or messages to Agent Zero from the phone (that's Telegram and web UI's job — Phase 4+ if ever)
- Editing scheduled tasks (v2)
- Cost charts or visualizations (v2 — numbers only in v1)
- Any on-device AI inference
- Solana Mobile Stack integration of any kind
- Solana wallet, blockchain interaction, x402, agent payments
- Device-as-capability (`/device/*` inverse API exposing GPS, camera, SMS, clipboard back to A0) — v2, but `/mobile/*` and the foreground service are designed to accommodate a sibling `/device/*` namespace without restructuring
- iOS, desktop, Wear OS, tablet-specific layouts
- Firebase, Crashlytics, telemetry of any kind
- Encryption of config at rest (no secrets)
- Play Store or dApp Store distribution
- In-app chat UI with A0
- Bulk action tiles on the Approvals tab (swipe-triage)
- Per-byte cellular bandwidth accounting
- Catchup *execution* of anything missed while offline (SZ approves; it never runs anything on its own)
- Multi-language support
- Light theme
- Widgets
- Merging with the benched Seeker dapp project

---

## v2 directions (architecture-affecting, worth knowing now)

### Device-as-capability inversion

SeekerClaw's "Android Bridge" exposes phone capabilities (GPS, camera, SMS, clipboard, TTS) to their on-device agent. The inverted version for SeekerZero: SeekerZero exposes a `/device/*` callback API that a0prod hits *through the tailnet* to get phone capabilities.

- A0 needs James's location for a task → calls SeekerZero → returns GPS
- A0 needs vision analysis → asks SeekerZero → phone captures a photo and uploads
- A0 wants to read or write clipboard → SeekerZero is the bridge

This makes SeekerZero a two-way client: UI inbound (you → A0), capabilities outbound (A0 → phone). Same tailnet, same auth, same foreground service (already running when toggled on).

This is the one idea from SeekerClaw worth architecting around now. Specifically: design `/mobile/*` and the foreground service so a sibling `/device/*` namespace can be added without restructuring. In practice this means the `MobileApiClient` and `SeekerZeroService` are both namespace-agnostic — they don't hardcode `/mobile` as the only API path.

### Session chat / recent A0 activity feed

Read-only feed of recent A0 activity (what the agent just did, what's running, what finished) on the home screen. Makes opening the app feel useful even with no pending approvals. v2.

### Bulk action tiles on Approvals

When 5+ approvals from the same source are pending, show a "Review all Gmail replies" tile that opens a focused queue view — swipe-right approve, swipe-left reject, undo bar. Same UX shape as email triage apps. v2 or v3.

---

## Bottom line

SeekerZero v1 is a thin Kotlin/Compose Android client that joins the existing tailnet, talks to a dedicated `/mobile/*` JSON API on Agent Zero, and gives the phone four surfaces: approvals (highest leverage, ties into Gmail Phase 3 and TaskMarket), scheduled task visibility (read-only), cost dashboard (numbers only), and diagnostics. The whole thing is architected around being *the opposite* of SeekerClaw — no on-device agent, no Node runtime, no subprocess, no cross-process IPC, no wallet, no secrets, no telemetry, minimal persistent state. The simplifications available to us because we chose this architecture are substantial and should be defended actively.

The build order is: scaffold repo and build config → create the shared UI component library → wire the first-run setup flow with QR → implement the foreground service and long-poll with retry policy → ship Phase 1 (approvals) end-to-end → then Phases 2, 3, 4 in order.
