# CLAUDE.md — SeekerZero Project Guide

> **Read `PLAN.md` before any feature work.** This file covers conventions and invariants. The plan covers architecture, API contracts, phase sequencing, and the reasoning behind every decision.

## What is this project

**SeekerZero** is a native Android app (Kotlin + Jetpack Compose) that runs on the Solana Seeker phone. It is a thin client for **Agent Zero** (the user's local AI orchestration system running on `a0prod` at `a0prod.your-tailnet.ts.net`). The phone joins the existing Tailscale tailnet and talks to Agent Zero over a dedicated `/mobile/*` JSON API. The app's job is to show pending approval gates, scheduled tasks, cost data, and diagnostics — and to let the user approve or reject gates from the lock screen.

SeekerZero does **not** run any AI on the phone, does **not** interact with the Solana blockchain, and does **not** talk to Telegram, Claude, OpenAI, or any third party. Everything flows through Agent Zero over the tailnet.

The project is personal, in-house, and sideload-only. No CI/CD, no Play Store, no dApp Store. Source is tracked in a private GitHub repo for change history only; distribution remains sideload via ADB.

## Tech stack

- **Language:** Kotlin 2.0
- **UI:** Jetpack Compose, Material 3, dark theme only
- **Min SDK:** 34 (Android 14), compileSdk 35, targetSdk 35
- **JVM:** Java 17
- **HTTP client:** Ktor or OkHttp (pick one at scaffolding, don't mix)
- **JSON:** kotlinx.serialization
- **QR scanning:** ZXing (`com.journeyapps:zxing-android-embedded`) — not ML Kit, not Google Play Services
- **Build:** Gradle (Kotlin DSL)
- **Target device:** Solana Seeker (also any Android 14+ phone for testing)

## Load-bearing invariants (do not violate)

These are the things that must stay true across every session. If a change would break any of these, stop and raise it instead of proceeding.

1. **Tailscale is the only transport.** No plaintext HTTP over the internet, no WireGuard, no custom TLS, no API keys, no OAuth, no bearer tokens. The tailnet is the auth layer.
2. **No secrets on the phone.** SharedPreferences only. No Android Keystore, no AES, no encryption at rest. Config is just a tailnet hostname and a client ID — nothing sensitive to protect.
3. **Service runs in the main app process.** No `:node` subprocess. No cross-process file-based IPC. All state is in-memory `StateFlow`. If a feature seems to need a second process, it doesn't.
4. **No telemetry, ever.** No Firebase, no Crashlytics, no analytics, no Google Play Services, no crash reporting, no phone-home. `LogCollector` writes to an in-memory ring buffer and a local file, period.
5. **No blockchain, no wallet, no Solana Mobile Stack.** SeekerZero is not a dapp. The Seeker is treated as a normal Android phone. If wallet features come up, they belong to the separate (benched) Seeker dapp project.
6. **Shared UI components only.** Screens use the components in `ui/components/` plus Material 3 primitives. No inline custom widgets. If you need a new shared component, add it to `ui/components/` first, then use it.
7. **`CardSurface` never takes a padding parameter.** It enforces background color and corner shape only. Callers pass padding via modifier. This rule was learned from SeekerClaw's debt audit; do not relearn it.
8. **Agent Zero is the only remote.** The app talks to `/mobile/*` on a0prod. It does not reach any other host.
9. **SeekerZero approves, it does not execute.** The app never runs tasks, executes code, drafts messages to send, or takes actions on the user's behalf beyond approving/rejecting gates that Agent Zero raised. Agent Zero does the work; the phone is a remote control.
10. **`specialUse` foreground service type.** Not `dataSync`, not `mediaPlayback`. With the matching `FOREGROUND_SERVICE_SPECIAL_USE` permission.

## Build & run

```bash
# From the project root
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk

# Clean build
./gradlew clean assembleDebug

# Check for lint issues
./gradlew lint
```

First build will download dependencies from Maven Central and Google's Android repository (Compose, Kotlin stdlib, CameraX, ZXing, kotlinx.serialization). This is unavoidable for Android builds. Subsequent builds are offline.

## Project structure

```
seekerzero/
├── app/
│   ├── src/main/
│   │   ├── java/dev/seekerzero/app/
│   │   │   ├── MainActivity.kt                  # Trivial Compose entry point
│   │   │   ├── SeekerZeroApplication.kt         # Two notification channels
│   │   │   ├── ui/
│   │   │   │   ├── theme/                       # Dark theme, Material 3
│   │   │   │   ├── components/                  # Shared UI component library (9 components)
│   │   │   │   ├── navigation/                  # NavGraph
│   │   │   │   ├── setup/                       # First-run QR scan + manual entry
│   │   │   │   ├── approvals/                   # Phase 1
│   │   │   │   ├── tasks/                       # Phase 2 (read-only)
│   │   │   │   ├── cost/                        # Phase 3 (read-only)
│   │   │   │   ├── diagnostics/                 # Phase 4
│   │   │   │   └── settings/
│   │   │   ├── service/                         # Foreground service + connection watchdog
│   │   │   ├── api/                             # /mobile/* client, long-poll, @Serializable models
│   │   │   ├── receiver/                        # BootReceiver
│   │   │   ├── config/                          # ConfigManager (SharedPrefs), QrParser
│   │   │   ├── qr/                              # QrScannerActivity (isolated)
│   │   │   └── util/                            # LogCollector, ServiceState (~20 lines, StateFlow only)
│   │   ├── res/values/strings.xml               # Mirrors docs/internal/TEMPLATES.md
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts                         # Target ~80 lines
├── build.gradle.kts                             # Root
├── settings.gradle.kts
├── gradle/libs.versions.toml
├── CLAUDE.md                                    # This file
├── PLAN.md                                      # Source of truth for architecture + phases
├── NOTICES                                      # SeekerClaw MIT attribution if any code copied
└── docs/internal/
    ├── SETTINGS_INFO.md                         # Mirrors SettingsHelpTexts.kt
    └── TEMPLATES.md                             # Mirrors strings.xml
```

## Shared UI component library

These nine components live in `ui/components/` and are the only way to build screens. Create them before any screen is built.

| Component | Purpose |
|---|---|
| `SeekerZeroTopAppBar(title, onBack)` | Material 3 TopAppBar with back arrow |
| `SeekerZeroScaffold(title, onBack, content)` | Scaffold wrapping TopAppBar + background + padding |
| `SectionLabel(title, action?)` | All-caps section header with optional trailing action |
| `ConfigField(label, value, onChange, trailingHelp?)` | Labeled text field with optional help "i" button |
| `InfoDialog(title, body, onDismiss)` | AlertDialog wrapper for help text |
| `CardSurface(modifier, content)` | Background color + corner shape. **No padding parameter.** |
| `InfoRow(label, value, dotColor?, isLast?)` | Key/value row with optional status dot and divider |
| `SettingRow(label, checked, onChange, info?)` | Label + toggle switch. Always uses `SeekerZeroSwitchColors`. |
| `StatusDot(color, size = 10.dp, pulsing = false)` | Circular indicator with optional pulse |

## Theme invariants

- One `SeekerZeroSwitchColors` pair in the theme object. All toggles go through `SettingRow`, which always uses it. No per-screen overrides. (SeekerClaw had a bug where LogsScreen used red and SettingsScreen used green for semantically identical toggles. Do not repeat.)
- One `SeekerZeroColors.Divider` for all dividers.
- Dark theme only in v1.
- Material 3 color tokens only. No raw hex in screens.

## Coding conventions

- **Compose over XML.** No layout XML files.
- **StateFlow over LiveData.** Observe via `collectAsStateWithLifecycle`.
- **One ViewModel per screen.** ViewModels talk to a single repository (`MobileRepository`) which wraps `MobileApiClient`.
- **No direct HTTP calls from screens or services.** Everything goes through `MobileApiClient`.
- **Never hardcode the tailnet host.** Read from `ConfigManager`, which reads from SharedPreferences.
- **Logs go through `LogCollector`.** No raw `Log.d` / `println` in production paths.
- **Kotlin coroutines + structured concurrency.** No `GlobalScope`. Every coroutine is scoped to a lifecycle owner or the service's own scope.
- **`@Serializable` data classes for every API model.** No untyped `JSONObject` parsing.
- **Every `/mobile/*` endpoint has a matching model class.** No ad-hoc JSON field access.

## Notification discipline

Two channels, created in `SeekerZeroApplication.onCreate`:

- `seekerzero_service` (LOW, silent, no badge): persistent "Connected to Agent Zero" notification while the foreground service is running.
- `seekerzero_approvals` (HIGH, sound, badge): new approval gates only.

**The only notifications that fire are approval-related.** Routine A0 events, scheduled task runs, cost threshold crossings, and heartbeats do **not** fire notifications. The phone stays quiet unless there is something the user genuinely needs to act on.

The foreground notification text is utilitarian: *"SeekerZero · Connected to Agent Zero"*. No emoji, no personality. This is a command surface, not a companion.

## File locations at runtime (on device)

```
/data/data/dev.seekerzero.app/
├── files/
│   ├── logs/                    # LogCollector output, rotated at 5MB
│   └── cache/                   # Cached approval details, cost data
└── shared_prefs/
    └── seekerzero_prefs.xml     # Config + toggle state + lastContactAtMs
```

No SQLite, no workspace directory, no skills folder. Intentionally minimal.

## What NOT to build (v1)

- Sending prompts or messages to Agent Zero from the phone. SeekerZero displays and approves; it does not chat.
- Editing scheduled tasks. Read-only.
- Cost charts or visualizations. Numbers only.
- On-device AI of any kind.
- Solana Mobile Stack, wallet, or blockchain features.
- iOS, desktop, Wear OS, tablet-specific layouts.
- Firebase, Crashlytics, analytics, telemetry.
- Config encryption at rest.
- Play Store / dApp Store distribution.
- In-app chat UI with A0.
- Multi-language support.
- Light theme.
- Widgets.
- A `padding` parameter on `CardSurface`.
- Cross-process IPC of any kind.

## Pitfalls learned from prior art (SeekerClaw)

- **Do not copy `ServiceState.kt` from SeekerClaw.** Theirs is ~330 lines because their service runs in a separate process. Ours runs in the main process. A ~20-line version with in-memory `StateFlow` is correct.
- **Do not copy their config encryption.** Tailscale removes the need. Plain SharedPreferences.
- **Do not copy their build flavors.** Single flavor, single signing config.
- **Do not copy their Firebase integration.** No telemetry at all.
- **Do not copy their NDK / CMake / nodejs download task.** No native code.

## Documentation conventions

- **`PLAN.md`** is the source of truth for architecture, API contracts, and phase sequencing. Long, comprehensive, rarely updated. Read before feature work.
- **`CLAUDE.md`** (this file) is conventions and invariants. Short, updated only when conventions change.
- **`docs/internal/SETTINGS_INFO.md`** is a markdown table mirroring `ui/settings/SettingsHelpTexts.kt`. Update markdown first, sync to Kotlin. The app reads only from Kotlin.
- **`docs/internal/TEMPLATES.md`** is a markdown table mirroring `res/values/strings.xml`. Update markdown first, sync to XML. The app reads only from strings.xml.

## When in doubt

- If the plan and this file disagree, the plan wins for content, this file wins for conventions.
- If something is not covered by either, ask before proceeding.
- If a decision would weaken any load-bearing invariant above, stop and raise it. Do not quietly relax an invariant to make a feature easier.
