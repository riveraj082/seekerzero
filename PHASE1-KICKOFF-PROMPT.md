# Phase 1 Kickoff Prompt — SeekerZero Scaffolding

> **How to use this file:** This is the prompt to paste into Claude Code for the first SeekerZero session. Copy everything below the horizontal rule into the Claude Code input. Do not include this header or the "How to use this file" line.

---

We are starting work on SeekerZero, a new Android app project. Before writing any code, read these two files in the project root and tell me back, in two or three sentences, what you understand the project to be:

1. `CLAUDE.md` — conventions, invariants, tech stack, project structure
2. `PLAN.md` — architecture, API contracts, phase sequencing

Then propose a plan for this session before touching any files. Wait for me to approve the plan before making changes.

## Session scope

This session is **Phase 1 scaffolding only**. No screens, no services, no API client, no real functionality. The goal is a clean project skeleton that builds and launches on the Seeker showing a blank activity, with the shared component library and build infrastructure in place.

Specifically, create exactly these things:

1. **Gradle project skeleton**
   - `build.gradle.kts` (root)
   - `settings.gradle.kts`
   - `gradle/libs.versions.toml` with locked versions for Kotlin 2.0, Compose BOM, Material 3, Navigation Compose, lifecycle-compose, activity-compose, kotlinx.serialization, CameraX, and ZXing embedded (`com.journeyapps:zxing-android-embedded:4.3.0`)
   - `gradle.properties`, `gradlew`, `gradlew.bat`, `gradle/wrapper/` — standard Android wrapper setup

2. **`app/build.gradle.kts`** — target around 80 lines, per the plan's Build Configuration section. Key points:
   - `compileSdk = 35`, `minSdk = 34`, `targetSdk = 35`
   - Java 17 source/target, Kotlin jvmTarget = "17"
   - `buildFeatures { compose = true; buildConfig = true }`
   - Plugins: `android.application`, `kotlin.android`, `kotlin.compose`, `kotlin.serialization`
   - Dependencies from the versions catalog only (no inline version strings)
   - `BuildConfig` fields injected at build time: `A0PROD_HOST` (default `"a0prod.<your-tailnet>.ts.net"`), `GIT_SHA` (short, via `providers.exec` if available; otherwise `"unknown"`), `BUILD_DATE` (ISO date)
   - ProGuard/R8 minify + shrinkResources enabled on release
   - **Do not include:** NDK, CMake, `externalNativeBuild`, `abiFilters`, product flavors, Firebase, Google Services plugin, Solana libraries, nanohttpd, any nodejs-mobile download task. Reject all of these if they come up.

3. **`app/src/main/AndroidManifest.xml`**
   - Package: `dev.seekerzero.app`
   - Permissions listed in the plan's Manifest section (FOREGROUND_SERVICE, FOREGROUND_SERVICE_SPECIAL_USE, RECEIVE_BOOT_COMPLETED, INTERNET, ACCESS_NETWORK_STATE, WAKE_LOCK, REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, POST_NOTIFICATIONS, CAMERA)
   - **Do not add:** SEND_SMS, CALL_PHONE, READ_CONTACTS, WRITE_CONTACTS, ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION. Those were for SeekerClaw's on-device agent; we reject them.
   - `android:allowBackup="false"` on the application
   - `SeekerZeroApplication` declared as the application class
   - `MainActivity` as the launcher activity
   - A stub `SeekerZeroService` declaration with `foregroundServiceType="specialUse"` and `exported="false"` — the service class itself will be a stub returning `null` from `onBind` and doing nothing else for now
   - **No `android:process=":node"` attribute on the service.** The service runs in the main app process.

4. **`SeekerZeroApplication.kt`** — application class that creates the two notification channels on `onCreate`:
   - `seekerzero_service`: `IMPORTANCE_LOW`, silent, `setShowBadge(false)`
   - `seekerzero_approvals`: `IMPORTANCE_HIGH`, default sound, `setShowBadge(true)`
   - Define the channel IDs as companion object constants
   - No analytics initialization, no Firebase, no telemetry setup. Nothing else in `onCreate`.

5. **`MainActivity.kt`** — trivial Compose entry point. `ComponentActivity`, `setContent { SeekerZeroTheme { Box { Text("SeekerZero") } } }`. That is the entire activity body for now. We will wire navigation in a later session.

6. **`ui/theme/`** — dark-only Material 3 theme:
   - `Theme.kt` with `SeekerZeroTheme` composable
   - `Color.kt` with the `SeekerZeroColors` object defining background, surface, card border, primary, error, warning, accent, text primary, text secondary — use reasonable dark-theme defaults, we can tune later
   - `Type.kt` with Material 3 typography defaults
   - `Shapes.kt` with `SeekerZeroShapes.Card` (rounded corner shape for CardSurface)
   - Define `SeekerZeroSwitchColors` once in the theme object; `SettingRow` will always use this
   - Define `SeekerZeroColors.Divider` for the single divider color convention

7. **`ui/components/`** — the nine shared components, per the plan's Shared UI Component Library section. Each component gets its own file, each with a `@Preview` so the component is verifiable in Android Studio without running the app:
   - `SeekerZeroTopAppBar.kt`
   - `SeekerZeroScaffold.kt`
   - `SectionLabel.kt`
   - `ConfigField.kt`
   - `InfoDialog.kt`
   - `CardSurface.kt` — **absolutely no padding parameter**. Box with background + shape only. Caller passes padding via modifier.
   - `InfoRow.kt`
   - `SettingRow.kt` — always uses `SeekerZeroSwitchColors` from the theme
   - `StatusDot.kt`

8. **`service/SeekerZeroService.kt`** — stub only for now. Extends `Service`, returns `null` from `onBind`. Do not implement the foreground service lifecycle, long-poll, or anything else this session. We will flesh it out in Phase 1 step 2.

9. **`proguard-rules.pro`** — empty file with a comment placeholder. We will add rules later if needed.

10. **`docs/internal/`** — create the directory with empty `SETTINGS_INFO.md` and `TEMPLATES.md` placeholder files, each with a one-line header noting that the real content comes in later phases.

## Out of scope this session — do not create

- `SetupScreen`, `QrScannerActivity`, or any QR scanning logic
- `ConfigManager` or SharedPreferences plumbing
- `MobileApiClient`, `LongPollClient`, any `/mobile/*` calls
- `ApprovalsScreen`, `TasksScreen`, `CostScreen`, `DiagnosticsScreen`
- `ConnectionWatchdog` or reconnection logic
- `BootReceiver`
- Any actual service implementation beyond the stub
- Navigation graph (NavHost)
- Any `@Serializable` model classes
- `LogCollector` implementation beyond an empty class
- `ServiceState` implementation beyond an empty singleton

If you finish the listed scope and want to do more, **stop and wait**. Ask what to do next. Do not scope-creep.

## Definition of done

The session is complete when all of the following are true:

1. The project directory structure matches the plan's Project Structure section.
2. `./gradlew assembleDebug` runs successfully and produces `app/build/outputs/apk/debug/app-debug.apk`. (If you do not have a device available, build-only is fine.)
3. The APK installs on an Android 14+ device and launches showing a blank activity with the text "SeekerZero".
4. The two notification channels exist after first launch. Verify via `adb shell dumpsys notification | grep seekerzero` — both `seekerzero_service` and `seekerzero_approvals` should be listed.
5. All nine shared components in `ui/components/` have working `@Preview` annotations that render in Android Studio's preview pane.
6. No warnings about missing foreground service type. No references to Firebase, Google Play Services, ML Kit, or Solana Mobile libraries anywhere in the build.
7. `CardSurface.kt` has no `padding` parameter. Verify this explicitly — it is a load-bearing invariant.

Report back with the commands you ran, the final file tree, and the output of `adb shell dumpsys notification | grep seekerzero` (or note if you couldn't run it).

## Rules for this session

- Read `CLAUDE.md` and `PLAN.md` before proposing a plan. Do not skip this.
- Propose a plan before writing code. Wait for approval.
- If the plan in `PLAN.md` and this prompt disagree, this prompt wins for session scope. `PLAN.md` wins for anything architectural that isn't covered here.
- Do not introduce any dependency not listed above. If you think another dependency is needed, stop and raise it.
- Do not create any file not listed above. If you think another file is needed, stop and raise it.
- Every load-bearing invariant in `CLAUDE.md` applies. If a choice would weaken one, stop and raise it.
- After making changes, re-read the files you changed before claiming they're done. Do not trust your memory of what you wrote.

Begin by reading `CLAUDE.md` and `PLAN.md`, then tell me what you understand this project to be and propose a plan for the session.
