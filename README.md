# Gameplay

Gameplay is an open-source Android gaming shell for installing, organizing, configuring, and running legally owned Windows games directly on ARM64 phones and handhelds.

It combines a controller-first console interface with isolated Wine/Proton containers. Games can come from a connected storefront or from files on the device: Gameplay treats local executables and installers as first-class library content rather than requiring every title to belong to Steam.

Gameplay is a modified fork of [GameNative](https://github.com/utkarshdalal/GameNative). It remains licensed under GPL-3.0, preserves upstream attribution, and develops a separate product direction focused on a unified console shell, local installation workflows, extensive theming, and equal support for touch and physical controllers.

This project uses AI generated code for design and theme engine.


<img width="834" height="887" alt="{3D316416-197D-442F-A4F2-363EDE8EA080}" src="https://github.com/user-attachments/assets/9dda553b-d0d0-40d0-80a0-05c2506df88f" />


> Gameplay is under active development. Compatibility varies by device, GPU driver, Android version, CPU architecture, Windows runtime, graphics translation layer, and the game itself. It is not a cloud-streaming service and does not include games, licenses, product keys, or DRM circumvention.

## What Gameplay is building

The goal is a complete Android console environment for Windows games—not a collection of disconnected emulator dialogs. Success means that importing, installing, configuring, and launching a game feels like one coherent workflow rather than operating a toolkit.

- A unified library for Steam, GOG, Epic, Amazon, imported games, and locally installed titles.
- A landscape shell designed for handheld consoles and controller navigation.
- Direct import of Windows executables, MSI packages, and EXE installers from Android storage.
- Automatic creation of a dedicated container when an installer is selected.
- Post-install executable discovery so an installed game can be added to the library and launched again.
- Reusable runtime caches so Wine/Proton components are not downloaded separately for every container.
- Per-game configuration for Wine/Proton, DXVK/VKD3D, GPU drivers, CPU translation, environment variables, display, and controls.
- Touch controls and physical Xbox-compatible and DualSense/PS5-compatible controllers as equal input methods.
- A semantic theme system with built-in themes, full-screen editing, import/export, validation, and recovery.

Release status and support scope are tracked through GitHub Releases and Issues.

## Current capabilities

### Games and installation

- Import local Windows executables into the library.
- Select an installer, create its container, run the installation, and continue into the normal Gameplay launch flow.
- Reopen installed games without repeating the installer process.
- Reuse cached runtime payloads across containers.
- Configure each title independently without changing the defaults for the rest of the library.
- View important runtime choices—graphics driver, Wine/Proton, and DXVK/VKD3D—close to the game configuration surface.
- Discover installed executables automatically after an installer finishes, so a finished title becomes a normal library entry with its own configuration.
- Apply community-submitted per-game configurations from the [Community Configs](https://github.com/intercontinentalmassacre/Gameplay) service, filtered by device and GPU, with full preview before applying.

ISO and other disc images are not currently treated as executables. They require a dedicated mounted-media workflow and remain planned work.

### Runtime stack

Windows software runs through an isolated container that combines a Wine/Proton-compatible environment with Android-native translation and graphics layers. The exact stack is selectable per game.

- **Wine and Proton** — a choice of Wine builds and Proton variants (including `x86_64` and `arm64ec` flavors) as the Windows compatibility layer.
- **CPU translation** — Box64/WOWBox64 for x86-64 emulation and FEX (ARM64EC) as an alternative backend; 32-bit translation rides on Box86/WoW64.
- **Graphics translation** — DXVK for Direct3D 9–11 over Vulkan, VKD3D-Proton for Direct3D 12, plus Zink/OpenGL-on-Vulkan paths for titles that need them.
- **GPU drivers** — bundled Mesa Turnip and vendor-specific wrappers, chosen per game and cached for reuse.
- **Frame generation** — optional LSFG-based Vulkan frame interpolation on capable hardware.
- **Audio** — PulseAudio inside the container routed to the Android audio stack.
- Components are versioned and validated; a runtime is never installed without a compatibility check.

### Storefronts and library

- Connect to Steam using the inherited GameNative integration.
- Retain installed library data and session state while navigating instead of reconnecting on every tab change.
- Hide storefront navigation when its service is not authenticated.
- Use installed games as the primary home library instead of a recommendations feed.
- Display Steam achievements in Gameplay's console-oriented game screen.
- Preserve GOG, Epic, and Amazon library support from the upstream codebase.
- Sort, filter, search, and select views through controller-friendly library options.
- Track game time against launch configurations; the running game is reported to Steam and session statistics are persisted into container metadata.

### Console interface

- Full-screen landscape navigation inspired by dedicated handhelds and living-room consoles without copying their visual identity.
- Separate system-menu and context-action behavior.
- Quick actions for play/install, details, search, library options, and adding games.
- Category-based application, container, and per-game settings.
- Full-screen nested settings instead of deep stacks of mobile dialogs.
- Restrained surfaces, low-saturation themes, predictable focus, and narrow-landscape layouts.
- Bottom-edge gamepad hint bars that always show which button does what, with 44dp minimum touch targets and reduced-motion fallbacks for every animation.

### In-game experience

- A quick menu for keyboard, on-screen controls, control editor, physical controller, radial menu, performance HUD, and exiting the game.
- Fully customizable radial menus and per-game input control profiles.
- On-screen touch controls plus Xbox-compatible and DualSense/PS5-compatible physical controllers.
- Performance HUD with live FPS; session averages (average FPS, session length) are written back to the container after exit.
- Steam achievements, cloud sync, and per-game container settings available without leaving the running session.
- A soft keyboard that follows the focus target and routes input to the external display when one is active.

### Diagnostics and crash reporting

- Structured crash logs are written per container, with sensitive values redacted before they are stored.
- A "Recent Crash" flow lets you attach the latest crash report to an email via a system share sheet, including app version and device information.
- On-device logs are self-contained: nothing is uploaded without your action.

### Themes

Gameplay themes are semantic documents rather than simple accent colors. A theme may redefine surfaces, text roles, focus states, status colors, shape, density, and related presentation tokens while retaining a safe recovery path.

Included profiles cover dark, OLED, light, forest, copper, wine, and arctic directions. Custom themes can be created in the application or imported from a file. The versioned format is documented in [docs/THEMES.md](docs/THEMES.md).

### Localization

The interface is translated into 15 locales in addition to English: Danish, German, Spanish, French, Italian, Japanese, Korean, Polish, Brazilian Portuguese, Romanian, Russian, Ukrainian, Simplified Chinese, and Traditional Chinese. Strings are maintained across all locales in parallel; interface changes must keep every translation file valid.

## Compatibility scope

Gameplay targets Windows software that can run through the bundled Wine/Proton-compatible stack and Android-native translation components.

- Modern 32-bit and 64-bit Windows games are supported when the selected runtime and hardware permit it.
- Vulkan-capable hardware is strongly recommended for modern DirectX games.
- Older DirectDraw/Direct3D titles may need game-specific graphics and Wine settings.
- Win16 support depends on the chosen Wine path and game architecture; it is not universal.
- DOS games need a future DOSBox-style integration and are not currently a promised compatibility target.
- Anti-cheat, kernel drivers, unusual DRM, launchers, codecs, and device-specific GPU issues may prevent a game from running.

There is no meaningful single “supported up to year X” cutoff. A demanding old title may fail while a newer title works well. Compatibility should be evaluated per game, runtime, driver, and device.

## Requirements

- ARM64 Android device.
- Android 10 or newer for the recommended `modern` build (`minSdk 29`, `targetSdk 36`).
- Android 8 or newer for the compatibility-oriented `legacy` build (`minSdk 26`, `targetSdk 28`).
- Sufficient free storage for the Windows runtime, container, installer, extracted files, and game data.
- Vulkan support for DXVK/VKD3D-based games.
- Legally obtained game files and any licenses required by their publishers.

The XR flavors are experimental and are not the primary Gameplay target.

## Documentation

- [DESIGN.md](DESIGN.md) — visual language, console focus vocabulary, color, and motion.
- [PRODUCT.md](PRODUCT.md) — product direction, user model, and accessibility targets.
- [docs/THEMES.md](docs/THEMES.md) — versioned theme format and authoring notes.
- [docs/GOG_API_SPEC.json](docs/GOG_API_SPEC.json), [docs/EPIC_API_SPEC.json](docs/EPIC_API_SPEC.json), [docs/AMAZON_API_SPEC.json](docs/AMAZON_API_SPEC.json) — storefront integration specs.
- [docs/PRODUCTION_PLAN.md](docs/PRODUCTION_PLAN.md), [docs/DEVELOPMENT_PLAN.md](DEVELOPMENT_PLAN.md) — roadmap and priorities.
- [UPSTREAM_ARCHITECTURE.md](UPSTREAM_ARCHITECTURE.md) — inherited GameNative architecture notes.

## Building from source

Use a current Android Studio installation with its bundled JDK. Clone submodules when obtaining a fresh checkout:

```sh
git clone --recurse-submodules https://github.com/intercontinentalmassacre/Gameplay.git
cd Gameplay
```

### Windows

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat :app:assembleModernDebug --no-daemon
```

### Linux and macOS

```sh
./gradlew :app:assembleModernDebug --no-daemon
```

The resulting APK is written to:

```text
app/build/outputs/apk/modern/debug/app-modern-debug.apk
```

Other available flavors include `legacy`, `legacyXr`, `modern`, and `modernXr`. Gameplay development primarily validates `modernDebug`. The XR flavors are experimental and are not the primary Gameplay target.

### Running tests

Gameplay ships unit and Robolectric tests that run without a device:

```sh
./gradlew :app:testModernDebugUnitTest --no-daemon
```

Covered areas include community config parsing, component catalog and manifest integrity, container configuration runtime behavior, best-config application, downloader validation, and launch-state analysis.

### Updates and releases

Gameplay checks the latest non-prerelease GitHub Release for `intercontinentalmassacre/Gameplay` at most once per day. An update is never downloaded without user approval. The release must contain these assets:

- `Gameplay-modern-release.apk`
- `Gameplay-modern-release.json`
- `SHA256SUMS.txt`

The APK is verified for package name, newer version code, size, and SHA-256 before Android's package installer is opened. The release workflow is [`.github/workflows/release.yml`](.github/workflows/release.yml). Production releases must use the same signing key as the installed `app.gameplay` package; losing that key makes in-place updates impossible.

On Windows, run `powershell -ExecutionPolicy Bypass -File .\scripts\release-preflight.ps1 -Tag v1.1.2` locally before publishing. It requires local `app/keystores/keystore.properties`, which must never be committed.

### Optional artwork integration

Automatic artwork lookup for imported games can use a SteamGridDB API key placed in `local.properties`:

```properties
STEAMGRIDDB_API_KEY=your_api_key_here
```

Never commit `local.properties`, API keys, signing credentials, keystores, or generated APKs.

## Development expectations

Gameplay is controller-first but not controller-only. Changes should preserve touch behavior and existing user data while moving ordinary navigation toward the shared console shell.

For interface changes, verify:

- narrow landscape screens;
- D-pad and analog focus traversal;
- Xbox A/B and PlayStation Cross/Circle semantics;
- Back behavior and focus restoration;
- touch targets, scrolling, empty states, and long localized strings;
- that every newly added string resource exists in all 16 locales.

For runtime changes, verify both a storefront title and a locally imported executable or installer where applicable. Container and database changes must preserve existing installations.

Before submitting code, build at least the primary variant:

```sh
./gradlew :app:assembleModernDebug --no-daemon
```

New bundled binaries must include their applicable license, notice, and reproducible source or source-offer information. Do not remove upstream copyright statements.

## Contributing

Issues and focused pull requests are welcome. Describe the device, Android version, SoC/GPU, selected driver, Wine/Proton version, graphics layer, and the exact stage that fails when reporting compatibility problems. Do not attach copyrighted games, credentials, authentication tokens, or private logs.

Large interface changes should preserve Gameplay's controller-first, touch-compatible product direction and document user-visible behavior in the pull request.

## License, attribution, and trademarks

Gameplay is distributed under the [GNU General Public License version 3](LICENSE). If you distribute a modified APK or other binary, you must satisfy the GPL-3.0 requirements, including providing the corresponding source under the same license.

GameNative and its contributors retain copyright in their original work. Gameplay's fork attribution is recorded in [NOTICE](NOTICE), and licenses or source information for bundled components are recorded in [THIRD_PARTY_NOTICES](THIRD_PARTY_NOTICES) and beside relevant assets.

Gameplay is not affiliated with or endorsed by Valve, Microsoft, Sony, Epic Games, GOG, Amazon, CodeWeavers, or the Wine project. Steam, Xbox, PlayStation, Windows, and other names and marks belong to their respective owners.
