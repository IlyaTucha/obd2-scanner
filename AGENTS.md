# AGENTS.md

This file provides guidance to Codex (claude.ai/code) when working with code in this repository.

## Project

Android app (Kotlin / Jetpack Compose + Material 3) for an ELM327 OBD2 Bluetooth scanner.
Real-time gauges, trip tracking with a Yandex map route, DTC diagnostics, and per-vehicle
profiles. The UI language and most code comments are **Russian** — match that when editing
user-facing strings and follow the existing comment style.

The Gradle project lives at the repo root (`obd2-scanner/`). Command line builds need
`JAVA_HOME` pointing to a JDK 17 (e.g. Android Studio's `jbr`). The original spec (the source of truth for feature intent) is `obd2-scanner/_private/ТЗ_OBD2_Android_App.md`;
section references like "ТЗ §13" in code comments point at it.

## Build & run

All commands run from `obd2-scanner/`:

```powershell
.\gradlew.bat assembleDebug          # build debug APK
.\gradlew.bat installDebug           # build + install on connected device/emulator
.\gradlew.bat lint                   # Android lint
.\gradlew.bat test                   # unit tests (JVM)
.\gradlew.bat test --tests "com.obd2monitor.SomeTest"   # single test class
.\gradlew.bat connectedAndroidTest   # instrumented tests (needs device)
```

- JDK 17, `compileSdk`/`targetSdk` 34, `minSdk` 26. No CI; verify changes by building locally.
- After completing app changes, check for a connected physical phone with `adb devices -l`.
  If the phone is authorized and online, build and install the debug APK on it and verify
  that installation succeeded before reporting completion.
- Dependencies are managed via the version catalog `gradle/libs.versions.toml` — add/update
  libraries there, then reference `libs.*` in `app/build.gradle.kts`.
- `YANDEX_API_KEY` is read from `local.properties` (not committed) into `BuildConfig`. The map
  in Trip Summary is blank without it, but the rest of the app builds and runs fine.

## Architecture

MVVM + Clean Architecture, single-module (`app`), Hilt DI throughout. Package root
`com.obd2monitor`, layered as `data/ · domain/ · ui/ · service/ · di/`.

- **Domain** (`domain/`): models, `repository/` interfaces, `usecase/UseCases.kt`. UI depends
  only on interfaces; `data/repository/*Impl` provide them, bound in `di/AppModule.kt`
  (`DatabaseModule`, `BluetoothModule`, `RepositoryModule`). All repositories are `@Singleton`.
- **UI** (`ui/`): Compose screens under `ui/screen/<feature>/` (each a `*Screen` + `*ViewModel`),
  reusable widgets in `ui/components/`, theme in `ui/theme/`. Navigation is centralized in
  `ui/navigation/AppNavigation.kt`.
- **Service** (`service/`): foreground trip recording + auto-session lifecycle (see below).
- Entry points: `OBDApplication` (`@HiltAndroidApp`) seeds the DB, starts `AutoSessionManager`,
  backs up the DB on every launch, and initializes Yandex MapKit. `MainActivity`
  (`@AndroidEntryPoint`) requests runtime permissions and triggers auto-connect.

### Navigation is a HorizontalPager, not a NavGraph of tabs

`AppNavigation` has a tiny `NavHost` (`Routes`): a single `tabs` destination plus detail/
trip-summary/history routes. The four "tabs" (Главная / Сессия / Ошибки / Настройки) are
**pages of a `HorizontalPager`** inside `TabsContainer`, driven by a custom `FloatingTabBar` —
there is no per-tab back stack. Notable quirks worth preserving:
- Detail screens reuse the **parent** `DashboardViewModel` via `hiltViewModel(parentEntry)` so
  sparkline history isn't reset when a detail screen opens.
- Tapping "Настройки" 6× in a row unlocks a hidden DB-backup menu (`SETTINGS_UNLOCK_TAPS`).
- "Авто" (car expense/mileage log) lives at the bottom of the Settings page, not as its own tab.

### OBD polling pipeline (`data/repository/OBDRepositoryImpl`)

This is the most subtle code in the app. `BluetoothObdConnection` (RFCOMM socket) →
`ELM327Protocol` (AT init, protocol negotiation) → `OBDParser` (hex → values) →
`PIDRepository` (supported-PID mask) → `FuelCalculator` (consumption). The repo exposes a
`liveData: SharedFlow<OBDData>` and a `connectionStatus: StateFlow<ConnectionStatus>`.

Key invariants — read the inline comments before changing any of this:
- **Tiered polling**: "hot" PIDs (speed/rpm/throttle/load/MAP/MAF/5E) every cycle; "slow" PIDs in
  round-robin groups, one of `SLOW_GROUPS` per cycle (Mode 01 gets the "1" response-count suffix
  when `probeSingleResponse` passes). Slow values are served from a cached `lastSnapshot` between reads so
  the UI doesn't flicker. `ifSupported(pid){}` skips unsupported PIDs (best-effort if mask empty).
- **Speed anti-spike** (`sanitizeSpeed`): ELM clones emit garbage speed bytes; a value is only
  accepted if physically reachable from the last accepted value, else it must be confirmed by the
  next snapshot. Don't "simplify" this away.
- **Reconnect logic**: a two-stage stale-watchdog inside the poll loop (light ELM re-init →
  hard socket re-create, escalated via `staleRecoveries`) plus a separate `scheduleRecovery()`
  for failed initial/manual connects. `reconnect()` deliberately routes CONNECTED → CONNECTING →
  INITIALIZING → CONNECTED **without** passing through DISCONNECTED, so the active trip isn't
  closed. Many comments here document real-world ELM "ghost connection" failure modes.

### Trip / session lifecycle (`service/`)

Sessions are **automatic**, coordinated through `TripController` (a `@Singleton` holding paused
flag, durationMs, pausedMs, and the finish-requested flag — survives leaving the screen):
- `AutoSessionManager` subscribes to `connectionStatus`: CONNECTED opens a trip, explicit user
  "Завершить" closes it, any other disconnect only **pauses** it (so stepping away / killing the
  engine resumes into the same trip). Watchdogs pause on stale data and auto-finish a trip paused
  longer than `AUTO_FINISH_PAUSE_MS` (8 h). On startup it adopts an orphaned open trip from the DB.
- `TripForegroundService` keeps recording alive in the background and writes `OBDReadingEntity`
  rows; `TripRepository` aggregates them into trip stats. GPS via Play Services location.
- GPS-only sessions: `AutoSessionManager.startGpsTrip()` (button on the Session tab) opens a trip
  in `TripController.gpsMode`; OBD disconnects don't pause it, the service writes GPS speed and
  auto-pauses after 5 min stationary. The GPS trip id is persisted in `UserPreferences` so an
  adopted orphan resumes in GPS mode. `finishByUser()` closes a trip in any state.
- `ActiveTripTracker` accumulates live stats (distance, fuel, route) incrementally from the
  service's writes — UI must not observe the full readings list of the active trip.
- Shared formulas: `data/obd/ReadingMetrics` (power/torque/fuel rate per reading) and
  `TripMath` (gap-aware distance, acceleration). Gaps > `TripMath.MAX_GAP_MS` are pauses.

### Persistence (`data/database/`)

Room DB `obd2_monitor.db`, `AppDatabase` schema **version 7**. Entities: trips, obd_readings,
vehicle profiles, DTC codes, and car-log tables (mileage/expense/maintenance).
- **Migrations are mandatory** — `AppDatabase.build` adds explicit `Migration`s and intentionally
  does **not** enable destructive migration. Bumping `version` without a migration will crash on
  upgrade (by design, to avoid silently dropping user history). Add a `Migration` for every schema change.
- `Seeder.seedIfEmpty` installs preset vehicle profiles (`VehicleProfile.presets()`) and a DTC
  set on first run; it inserts only when a row's id is absent (no upsert) to avoid clobbering
  user-edited fields, and prunes obsolete preset ids. The DTC table is rewritten from
  `DtcLibrary.all()` on every launch: SAE families generated from templates, with hand-written
  `BuiltinDtcCatalog` entries taking precedence.
- `DatabaseBackupManager` / `OBDApplication.backupDatabase` copy the DB (after a WAL checkpoint)
  to `Android/data/<pkg>/files/db_backups` at most once a day, gzip-compressed as `.db.gz` (keeps last 3).

## Conventions

- Comments and UI strings are in Russian; preserve tone and density when editing nearby code.
- Repository methods that touch the BT socket serialize through `ioMutex`; respect it to avoid
  interleaving ELM commands.
- Constants that encode the same business rule in two places (e.g. `AUTO_FINISH_PAUSE_MS` shared
  between `TripController` and `AutoSessionManager`) are cross-referenced in comments — keep them in sync.
