# Phase 1 Step 2 Kickoff Prompt — First-Run Setup Flow & Nav Shell

> **How to use this file:** This is the prompt to paste into Claude Code for the second SeekerZero session. Copy everything below the horizontal rule into the Claude Code input. Do not include this header or the "How to use this file" line.

---

We are continuing SeekerZero, an Android app project. Phase 1 Step 1 (scaffolding) has already shipped — the repo, Gradle skeleton, manifest, two-channel `SeekerZeroApplication`, trivial `MainActivity`, dark Material 3 theme, and all 9 shared UI components are already on disk and building green.

Before writing any code, read these files in the project root and tell me back, in two or three sentences, what you understand the project state to be:

1. `CLAUDE.md` — conventions, invariants, tech stack, project structure
2. `PLAN.md` — architecture, API contracts, phase sequencing
3. `PHASE1-KICKOFF-PROMPT.md` — the prior session's scope (what already exists)
4. The current `app/` tree — especially `MainActivity.kt`, `ui/components/`, `ui/theme/`, and the stub `service/SeekerZeroService.kt`

Then propose a plan for this session before touching any files. Wait for me to approve the plan before making changes.

## Session scope

This session is **the first-run setup flow and the navigation shell**. By the end, a freshly installed APK launches, detects no stored config, sends the user through QR scan → health check → permission dialogs → lands on a `MainScaffold` with four empty tab stubs. The foreground service stays a stub. Approvals UI stays a stub. The app does not long-poll. What it does is prove end-to-end that the Seeker can discover and reach Agent Zero over the tailnet.

Specifically, create exactly these things:

1. **`config/ConfigManager.kt`**
   - Wraps `SharedPreferences` for `seekerzero_prefs`.
   - Stores: `a0_host: String?`, `mobile_api_base: String` (default `"/mobile"`), `client_id: String?`, `display_name: String?`, `last_contact_at_ms: Long`, `service_enabled: Boolean` (default false).
   - Exposes each as a read/write property plus a `StateFlow` for observers.
   - Single method `isConfigured(): Boolean = a0_host != null && client_id != null`.
   - No Android Keystore. No encryption. Plain SharedPreferences per load-bearing invariant #2.

2. **`config/QrParser.kt`**
   - Pure Kotlin object with a single `parse(rawPayload: String): Result<QrConfigPayload>` function.
   - Accepts either:
     - A bare base64url-encoded JSON blob, or
     - A `seekerzero://config?payload=<base64>` URI.
   - Decodes → validates schema version `v == 1` → returns `@Serializable data class QrConfigPayload(val v: Int, val a0_host: String, val mobile_api_base: String, val client_id: String, val display_name: String)`.
   - On any failure returns `Result.failure` with a typed exception so the caller can surface a clear error.

3. **`api/MobileApiClient.kt` + `api/models/`**
   - HTTP client — **OkHttp**, not Ktor. One client instance, injected via simple object/singleton for now (DI framework is not in scope).
   - Base URL built from `ConfigManager.a0_host` + `ConfigManager.mobile_api_base`. Never hardcode the host.
   - One method for this session: `suspend fun health(): Result<HealthResponse>` hitting `GET /mobile/health`.
   - `@Serializable data class HealthResponse(...)` — minimal shape for now (see server-side spec below).
   - 10s connect timeout, 10s read timeout. No retry logic this session (that lands in Step 3 with the long-poll).
   - All JSON via `kotlinx.serialization`. No `JSONObject`.

4. **`qr/QrScannerActivity.kt`**
   - Uses `com.journeyapps.barcodescanner.CaptureActivity` pattern — launches a ZXing-embedded scanner, returns the decoded string via `setResult(RESULT_OK, ...)`.
   - Portrait-only, `excludeFromRecents=true` in the manifest.
   - Handles camera permission itself (denial → finish with `RESULT_CANCELED`).
   - Does not parse the QR — returns the raw string to the caller (`SetupScreen`).

5. **`ui/setup/SetupScreen.kt` + `ui/setup/SetupViewModel.kt`**
   - Single-screen flow with these states, all inside one Composable switching on `SetupUiState`:
     - **Welcome:** brief one-paragraph explanation + "Scan QR" primary button + small "Can't scan?" link for manual entry fallback.
     - **Scanning:** launches `QrScannerActivity` via `rememberLauncherForActivityResult`. On result, hands raw string to VM.
     - **Verifying:** spinner while VM parses payload → saves to `ConfigManager` → calls `MobileApiClient.health()`.
     - **Health failed:** error card with the underlying message + "Retry" + "Start over".
     - **Request notification permission:** only on API 33+; uses `rememberLauncherForActivityResult(RequestPermission)`. Skippable (user can say no and the app still works, just without notifications).
     - **Battery optimization exemption:** shows an info dialog with "Open settings" CTA that fires `Intent(ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)`. Skippable.
     - **Done:** one-line success, auto-navigates to `MainScaffold` on dismiss.
     - **Manual entry (fallback):** `ConfigField` for tailnet host and optional client_id. Saves to `ConfigManager`, skips the QR payload path, goes straight to Verifying.
   - Uses **only** components from `ui/components/` plus Material 3 primitives. No inline custom widgets (invariant #6).
   - `SetupViewModel` exposes a single `StateFlow<SetupUiState>` and discrete intent functions (`onScanResult(raw: String)`, `onRetryHealth()`, `onStartOver()`, `onManualEntry(host, clientId)`, `onDismissDone()`).

6. **`ui/navigation/NavGraph.kt`**
   - `SeekerZeroNavHost` composable with two routes: `"setup"` and `"main"`.
   - Start destination picked at composition time: `if (ConfigManager.isConfigured()) "main" else "setup"`.
   - `SetupScreen` calls `navController.navigate("main") { popUpTo("setup") { inclusive = true } }` on done.

7. **`ui/main/MainScaffold.kt`**
   - Material 3 `Scaffold` with a bottom `NavigationBar` of four items: Approvals, Tasks, Cost, Diagnostics.
   - Nested `NavHost` switching between four stub Composables: `ApprovalsScreenStub`, `TasksScreenStub`, `CostScreenStub`, `DiagnosticsScreenStub` — each one just renders `SeekerZeroScaffold(title = "...")` with a centered placeholder using `SectionLabel` or `Text`. No real data fetching this session.
   - Stubs live as private Composables at the bottom of `MainScaffold.kt`. Do not scaffold separate screen files for Phase 2/3/4 yet.

8. **`util/LogCollector.kt`**
   - In-memory ring buffer (size 500) + `files/logs/seekerzero.log` with rotation at 5MB (one backup).
   - API: `fun d(tag: String, msg: String)`, `i`, `w`, `e(tag, msg, throwable?)`. Plus `fun recent(): List<LogLine>`.
   - All SeekerZero code goes through this. No raw `Log.d` / `println`.

9. **`util/ServiceState.kt`**
   - ~20 lines. Singleton with in-memory `StateFlow`s: `connectionState: ConnectionState`, `pendingApprovals: List<Approval>` (type stubbed as `Any` or empty data class for now), `lastContactAtMs: Long`, `reconnectCount: Int`.
   - `enum class ConnectionState { DISCONNECTED, PAUSED_NO_NETWORK, RECONNECTING, CONNECTED, OFFLINE }`.
   - No logic, no observers wired — just the skeleton. Step 3 populates these.

10. **`MainActivity.kt` edits**
    - Replace the trivial `Box { Text("SeekerZero") }` body with `SeekerZeroNavHost()` inside `SeekerZeroTheme`.
    - That is the only change to `MainActivity`.

11. **`AndroidManifest.xml` edits**
    - Add `QrScannerActivity` declaration: portrait, `excludeFromRecents="true"`, `exported="false"`.
    - Add camera feature declaration: `<uses-feature android:name="android.hardware.camera" android:required="true" />`.
    - No other manifest changes.

12. **Server-side `/mobile/health` stub on a0prod**
    - New endpoint registered under the existing Agent Zero web UI's FastAPI/Flask routing (inspect `/a0/usr/` extensions or wherever routes live — search for existing `webui` route registration pattern before writing).
    - Tailscale peer check: accept only requests whose source IP is on the tailnet (100.x.y.z). Reject others with 403.
    - Response shape:
      ```json
      {
        "ok": true,
        "server_time_ms": 1745000000000,
        "a0_version": "0.9.8.2",
        "subordinates": [
          {"name": "a0-think", "status": "up", "last_response_ms": 420},
          {"name": "a0-work", "status": "up", "last_response_ms": 180},
          {"name": "a0-embed", "status": "up", "last_response_ms": 95}
        ]
      }
      ```
    - Subordinate list can be hardcoded to the three models from `project_seekerzero.md` memory for now — real status polling lands in Phase 4 Diagnostics.
    - **Do not** implement `/mobile/approvals/*`, `/mobile/tasks/*`, `/mobile/cost/*`, or `/mobile/config/qr` this session. Just `/mobile/health`.

13. **`docs/internal/TEMPLATES.md`** update
    - Add a small markdown table with the user-facing strings introduced by SetupScreen (welcome copy, scan prompt, health-failed message, permission rationale). Mirror these into `strings.xml`. App reads from `strings.xml`; markdown is the human-readable surface.

## Out of scope this session — do not create

- `SeekerZeroService.kt` **real implementation** (foreground, long-poll, retry, reconnection). Stays a stub. Phase 1 Step 3 owns this.
- `LongPollClient.kt`.
- `ConnectionWatchdog.kt`.
- `BootReceiver.kt` (restoring service-enabled toggle on boot).
- `ApprovalsScreen.kt` **real implementation** (list, detail, approve/reject). Stays a stub in `MainScaffold`. Phase 1 Step 4 owns this.
- Notification pipeline — no `NotificationCompat.Builder`, no inline action buttons. Channels are already created; that's enough for now.
- `@Serializable` models for `Approval`, `ScheduledTask`, `CostSummary`, `Diagnostics`. Only `HealthResponse` and `QrConfigPayload` this session.
- Server-side `/mobile/approvals/*`, `/mobile/tasks/*`, `/mobile/cost/*`, `/mobile/config/qr`.
- Multi-client coexistence / client registry changes on a0prod.
- DI framework (Hilt, Koin). Plain singletons and object holders are fine.
- Unit tests. (We'll add them once there's non-trivial logic to test; right now most of the code is UI plumbing.)
- Custom app icon. Default robot is fine.
- Settings screen.

If you finish the listed scope and want to do more, **stop and wait**. Ask what to do next. Do not scope-creep.

## Definition of done

The session is complete when all of the following are true:

1. `./gradlew assembleDebug` runs successfully with no new warnings beyond those present after Step 1.
2. Fresh install on emulator launches into `SetupScreen`. Completing a QR scan against a0prod's `/mobile/health` endpoint lands the user in `MainScaffold` with four tab stubs visible.
3. Re-launching the app after setup skips `SetupScreen` and goes straight to `MainScaffold`.
4. `/mobile/health` on a0prod returns the JSON shape above over the tailnet. `curl -s http://a0prod.your-tailnet.ts.net/mobile/health` from another tailnet peer prints valid JSON with `"ok": true`.
5. Manual-entry fallback works (no camera available → user types the host → same flow lands on `MainScaffold`).
6. Notification-permission and battery-optimization dialogs are presented on API 33+ and are skippable without breaking navigation.
7. No direct HTTP calls outside `MobileApiClient`. No raw `Log.d` / `println` — all logs go through `LogCollector`. No hardcoded tailnet host anywhere in code.
8. `CardSurface` still has no `padding` parameter (re-verify).
9. No new references to Firebase, Google Play Services, ML Kit, or Solana Mobile libraries (re-verify).
10. Synced back to a0prod canonical tree and committed + pushed to the `seekerzero` repo on GitHub.

Report back with the commands run, updated file tree, `curl` output for `/mobile/health`, and a note on whether the end-to-end QR-scan flow worked on emulator.

## Rules for this session

- Read `CLAUDE.md`, `PLAN.md`, `PHASE1-KICKOFF-PROMPT.md`, and survey the existing `app/` tree before proposing a plan. Do not skip this.
- Propose a plan before writing code. Wait for approval.
- If the plan in `PLAN.md` and this prompt disagree, this prompt wins for session scope. `PLAN.md` wins for anything architectural that isn't covered here.
- Do not introduce any dependency not already declared in `gradle/libs.versions.toml`. If you think one is needed, stop and raise it.
- Do not create any file not listed above. If you think another file is needed, stop and raise it.
- Every load-bearing invariant in `CLAUDE.md` applies. If a choice would weaken one, stop and raise it.
- After making changes, re-read the files you changed before claiming they're done. Do not trust your memory of what you wrote.
- Windows working tree is canonical for builds; a0prod mirror at `/home/a0user/agent-zero-data/projects/seekerzero/` must be kept in sync via `tar | ssh` after each meaningful change.

Begin by reading the four source documents listed above, then tell me what you understand the project state to be and propose a plan for the session.
