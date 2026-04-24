# CLAUDE.md — SeekerZero Project Guide

> **Read `PLAN.md` before any feature work.** This file covers conventions and invariants. The plan covers architecture, API contracts, phase sequencing, and the reasoning behind every decision.

## What is this project

**SeekerZero** is a native Android app (Kotlin + Jetpack Compose) that runs on the Solana Seeker phone. It is a full **Agent Zero** client (local AI orchestration system running on `a0prod` at `a0prod.<your-tailnet>.ts.net`). The phone joins the existing Tailscale tailnet and talks to Agent Zero over a dedicated `/mobile/*` JSON API plus direct SSH to the a0prod host. The app's v1 surfaces are: **Chat** (freeform prompts with token-streamed responses), **Approvals** (shipped: pending gates + approve/reject), **Tasks** (read + create scheduled tasks), and **Terminal** (SSH pty to a0prod).

SeekerZero does **not** run any AI on the phone, does **not** interact with the Solana blockchain, and does **not** talk to Telegram, Claude, OpenAI, or any third party directly. All AI work happens server-side in Agent Zero; the phone is the control surface.

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

1. **Tailscale is the only network transport.** No plaintext HTTP over the internet, no WireGuard-beside-Tailscale, no custom TLS, no Claude/OpenAI API keys, no OAuth, no bearer tokens. The tailnet is the network auth layer for `/mobile/*` and for SSH to a0prod.
2. **Minimal secrets on the phone, key material only in Keystore.** The SSH private key is the only secret that lives on the device; it stays in Android Keystore (hardware-backed where possible), never the filesystem. SharedPreferences holds config + toggle state + last-contact timestamps; the Room chat cache holds recent messages. No Claude/OpenAI API keys, no `secrets.env` contents, no Telegram tokens, no wallet material — ever.
3. **Service runs in the main app process.** No `:node` subprocess. No cross-process file-based IPC. All state is in-memory `StateFlow` or a small Room DB (chat cache). If a feature seems to need a second process, it doesn't.
4. **No telemetry, ever.** No Firebase, no Crashlytics, no analytics, no Google Play Services, no crash reporting, no phone-home. `LogCollector` writes to an in-memory ring buffer and a local file, period.
5. **No blockchain, no wallet, no Solana Mobile Stack.** SeekerZero is not a dapp. The Seeker is treated as a normal Android phone. If wallet features come up, they belong to the separate (benched) Seeker dapp project.
6. **Shared UI components only.** Screens use the components in `ui/components/` plus Material 3 primitives. No inline custom widgets. If you need a new shared component, add it to `ui/components/` first, then use it.
7. **`CardSurface` never takes a padding parameter.** It enforces background color and corner shape only. Callers pass padding via modifier. This rule was learned from SeekerClaw's debt audit; do not relearn it.
8. **Agent Zero is the only remote over `/mobile/*`; SSH goes directly to a0prod.** The app does not reach any other host. A0 doesn't mediate the terminal session — that's plain OpenSSH on a0prod, keyed by the per-device Ed25519 pair.
9. **`specialUse` foreground service type.** Not `dataSync`, not `mediaPlayback`. With the matching `FOREGROUND_SERVICE_SPECIAL_USE` permission.
10. **Biometric gate on Terminal tab.** Opening TerminalScreen requires `BiometricPrompt` (fingerprint or face). Cached for ~5 minutes of active terminal use. This is the non-negotiable defense against "phone handed to someone for 30 seconds" becoming shell access on a0prod.
11. **SSH keygen is on-device only; never import or export keys.** Keypair is created in Keystore on first run; the public key is displayed for user copy-paste into a0prod's `authorized_keys`; the private key never leaves the phone. If the phone is reset or reinstalled, a new keypair is generated and the old line in `authorized_keys` is stale and should be removed.

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
│   │   │   │   ├── setup/                       # First-run QR + SSH pubkey enrollment
│   │   │   │   ├── approvals/                   # Phase 1 (shipped)
│   │   │   │   ├── chat/                        # Phase 5
│   │   │   │   ├── tasks/                       # Phase 7 (read + create)
│   │   │   │   ├── terminal/                    # Phase 6
│   │   │   │   └── settings/
│   │   │   ├── service/                         # Foreground service + connection watchdog
│   │   │   ├── api/                             # /mobile/* client, @Serializable models
│   │   │   ├── chat/                            # ChatRepository + Room DB
│   │   │   ├── ssh/                             # Keystore Ed25519, sshj client, known_hosts
│   │   │   ├── receiver/                        # BootReceiver
│   │   │   ├── config/                          # ConfigManager (SharedPrefs), QrParser
│   │   │   ├── qr/                              # QrScannerActivity (isolated)
│   │   │   └── util/                            # LogCollector, ServiceState
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

Three channels, created in `SeekerZeroApplication.onCreate`:

- `seekerzero_service` (MIN, silent, no badge): persistent "Connected to Agent Zero" notification while the foreground service is running.
- `seekerzero_chat` (HIGH, sound, badge): Agent Zero chat replies. Fires **only** when the app is backgrounded; in-foreground replies stay silent because the chat screen renders them inline.
- `seekerzero_scheduled` (HIGH, sound, badge): results of scheduled tasks routed to this phone via the scheduler's `delivery_channel = "seekerzero"` (or `"both"`).

The prior `seekerzero_approvals` channel was removed and is explicitly deleted on app start. Approvals v1 was cut; if it comes back, it can reuse `seekerzero_scheduled` or add a fourth channel.

Notifications fire only for events the user genuinely cares about being pinged on: a chat reply they're waiting for, or a scheduled task result routed to the phone. Routine A0 events (tool calls mid-turn, subordinate spawns, health pings, errors not tied to a user-initiated delivery) do NOT fire notifications. The foreground-service notification is MIN-importance and silent.

Tapping a chat-reply or scheduled-delivery notification deep-links into the chat tab (scheduled deliveries default to the current chat context; chat-reply notifications switch to the context that produced them).

The foreground notification text is utilitarian: *"SeekerZero · Connected to Agent Zero"*. No emoji, no personality. This is a command surface, not a companion.

Notification controls live on the Status tab under the "Notifications" card: per-channel system-settings shortcuts, battery-optimization exemption, and two test-notification buttons.

## File locations at runtime (on device)

```
/data/data/dev.seekerzero.app/
├── files/
│   ├── logs/                    # LogCollector output, rotated at 5MB
│   └── cache/                   # Cached approval details, task detail
├── databases/
│   └── chat.db                  # Room: messages + known_hosts (SSH TOFU)
└── shared_prefs/
    └── seekerzero_prefs.xml     # Config + toggle + lastContactAtMs + last-seen-chat-id
```

Secrets are NOT in any of the above. The SSH private key lives in **Android Keystore** (alias `seekerzero_ssh_v1`), never the filesystem. No Claude/OpenAI API keys, no wallet material, no skills folder, no workspace.

## What NOT to build (v1)

- Cost dashboard or charts (cut from v3).
- Standalone Diagnostics tab (inlined on other tabs / Settings instead).
- Editing existing scheduled tasks (create + enable/disable + run-now are in scope; mutating cron/prompt after the fact is v2).
- On-device AI of any kind.
- Solana Mobile Stack, wallet, or blockchain features.
- iOS, desktop, Wear OS, tablet-specific layouts.
- Firebase, Crashlytics, analytics, telemetry.
- Play Store / dApp Store distribution.
- Multi-language support.
- Light theme.
- Home-screen widgets.
- A `padding` parameter on `CardSurface`.
- Cross-process IPC of any kind.
- Password-authenticated SSH (key-only).
- Importing SSH keys generated elsewhere (on-device keygen only).
- Agent forwarding over the terminal SSH session.
- Remote kill-switch for the app (beyond Tailscale device revocation).

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
