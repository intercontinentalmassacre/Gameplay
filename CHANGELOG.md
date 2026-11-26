# Changelog

All notable changes to Gameplay are documented here.

## 0.6 — 2026-11-26

- Connect itch.io from the sign-in screen or system hub. Its library tab appears after sign-in. Install and update supported Windows ZIP builds and installers; ZIP updates preserve changed player files and save a separate copy when the new build supplies the same path.
- Sync cloud saves when leaving a game. If an upload is interrupted, the next launch can retry the sync. Local saves are backed up before supported cloud downloads, and previous backups can be restored from the game menu.
- Keep pending Steam launches queued through sign-in and Steam Guard. Improve container crash diagnostics and compatibility with Common Controls v6, 32-bit CPU affinity changes, and Adreno 85x drivers.
- Add experimental FEX translation-cache preparation and recompilation from recorded code maps.
- Fix a crash when Android pauses the activity after PulseAudio has already stopped. Keep the network receiver registered to the application rather than the activity.

## 0.5 — 2026-11-25

- Choose a physical controller preset per game, or search Steam community layouts and import supported buttons, sticks and triggers.
- Use the companion screen as a touchpad and keyboard while the game keeps running. Touchpad clicks give haptic feedback.
- Launch games with small executables declared by Steam, including Haiku, the Robot.
- Handle interrupted Steam Cloud uploads and keep the local save available for another sync.

## 0.4.1 — 2026-11-24

- Choose a compatibility preset and DXVK version while installing supported Steam and Epic games. Eligible Wine containers can share a base for common components.
- Customize physical-controller bindings and optionally record Vulkan pipelines for reuse on later launches.
- Import and manage local mods, including per-game profiles and file priority. Online Nexus Mods access remains disabled.
- Export a per-game support bundle with launch diagnostics.
- Fix Android build-check tests and run both legacy and modern debug checks on pull requests to `working-builds`.

## 0.4 — 2026-11-21

### Graphics, drivers and game profiles

- Added per-container selection of X11 or Gamescope. Gamescope offers output resolution, scaling mode, sharpness, FSR, NIS, SGSR, colour filters, ReShade effects and beta HDR output.
- Added an opt-in experimental Gamescope runtime for OpenGL presentation on Adreno and frame-generation pacing under an FPS cap. The quick menu exposes scaling and presentation controls. Updated the native LSFG runtime and its Android packaging.
- Updated X11 root visual and pixmap format reporting for 24-bit colour in a 32-bit pixel, and updated the Vulkan renderer binaries in the modern and legacy packages.
- Added an Arm Mali category with MaliSX2 0.5.1, FristOneRR PanVK 3.7.0 and Mesa PanVK 26.3.0 panvk.8. Updated the Turnip catalog with Gen8 V38, A8XX v29, mainline v30/v31 and additional Mesa builds.
- Bundled two presets in the APK. Open a game and choose **Options → Container → Game profiles**. The JSON files are also available in the [profile catalog](https://github.com/intercontinentalmassacre/Gameplay/tree/v0.4/catalog/profiles). **Mali DX9 System** uses the Android system driver, Bionic, Proton 11 ARM64EC and DXVK-Sarek 1.11.1. It leaves resolution, memory budget and FPS to the game or container.
- **Neo Pocket System DX9** targets the 3 GB handheld with 640×480, a 30 FPS target, a 256 MB DXVK budget, one compiler thread and one frame of latency. Its separate DXVK component includes the 32-bit Sarek 1.14.1 D3D9 DLL with a command-buffer query-reset fallback and changes to index-buffer mapping. Selecting the built-in Pocket preset installs its bundled DXVK component if needed and applies X11 and 640×480.
- Added DXVK 3.0 through 3.1.1, GPLAsync and binary-semaphore variants, VKD3D 3.1 and Proton 11.0-2 builds for x86-64 and ARM64EC to the component catalog. Updated DXVK inline configuration and shared D8VK packaging.

### Game launch, containers and audio

- Added import and native Linux launch for ARM64 Type 2 AppImages. The first launch prepares a shared Linux runtime. The local game picker accepts EXE and AppImage files and gives recovery instructions when Android cannot grant access to a selected folder.
- Moved container settings loading off the UI thread and added a visible error state when settings cannot be opened. Updated runtime and driver validation, component installation and lookup of compatible game configurations.
- Added an optional suspend mode that reclaims memory from paused Windows game processes and resumes them automatically on supported kernels.
- Updated FEXCore runtime handling, pre-install and pre-launch setup, and Proton common-controls v6 handling.
- Added the GNAudio guest runtime and low-latency setting, and packaged DirectAudio 1.3.2 for Wine 11.

### Steam, downloads and cloud saves

- Queued and batched Steam app and package metadata requests. Incomplete requests can resume after reconnect or timeout, and library refresh uses batched database reads for license and metadata checks.
- Updated family-library and licensed-depot handling, branch-specific launch entries and executable selection. Cancelling an update preserves the installed game and guards against treating the library root as a game directory.
- Steam Auto-Cloud compares resolved save destinations and handles remote change counters moving backwards. Identical saves can repair the counter. For conflicting saves, Keep Local resubmits local files and Keep Remote uses the cloud copy.
- Updated download progress handling, component preparation, cover caching, network cancellation and cleanup of temporary attestation keys.

### Handheld interface and input

- Updated the console library and dual-screen companion with game artwork, network and battery status, Steam features and controller support indicators. Refined focus, gamepad actions, downloads and quick-menu behaviour.
- Left and right Nintendo Joy-Con controllers can share a player slot. Disconnecting one half preserves the assignment of the other.
- Updated external-display switching, touch and keyboard input routing. Shifted characters entered through the secondary-screen IME now carry the required modifier in Gamescope.
- Updated translated labels and error messages for the container, driver, download and display flows.

### Device results and known limits

- On the 3 GB AYANEO Pocket AIR Mini, Batman: Arkham Asylum GOTY displayed its 3D main menu over X11 with the system Mali driver. Removing the game's movie-skip option restored the opening videos and loading artwork. The user confirmed their Arkham Mansion save reached gameplay. Loading was slow under memory pressure. A repeatable gameplay FPS figure and a measured speedup from the lower texture settings are still unavailable.
- The experimental MaliSX2 path could draw the menu after changes to clip-distance handling, indexed draws and query reset, but later reported device loss during the level transition. The working Pocket profile selects the system driver.
- A realme RMX3241 user on Android 13 reported that Call of Duty 4: Modern Warfare stayed at launch after interrupted setup. The cause is unconfirmed. Mission startup for that game and Modern Warfare 2 (2009) on the realme remains unverified.

### Build and regression coverage

- Updated JavaSteam to 1.8.0.1-26-SNAPSHOT, modern and legacy asset packaging, component manifests, native libraries, and third-party notices. Added a packaging script and build notes for experimental Gamescope.
- Added regression coverage for Steam metadata, saves and executable selection, AppImage recognition, container configuration, package and driver handling, DXVK options, FEXCore, controller pairing, suspended-process memory and crash handling. Added Android instrumentation coverage for display and X server behaviour.

## 0.2 — 2026-08-15

### Added

- Native Android game install and launch support, with per-platform depot selection and modern-build glibc containers.
- Console-focused Steam game cards with richer store metadata and improved artwork selection.
- A manual library refresh that synchronizes Steam, GOG, Epic Games Store, and Amazon Games, plus connected-account identity in the system hub.
- A companion experience for dual-screen handhelds, covering library browsing, downloads, settings, quick actions, game details, login, and configuration.
- A dedicated downloads workspace with container-component prefetching and clearer runtime availability.
- DirectAudio runtime support and an updated driver catalog and driver-management workflow.
- Expanded Steam Controller support: profile import, bindings, action layers, keyboard, menus, haptics, BLE transport, and configuration editing.
- Secure at-rest storage for Steam, GOG, Epic, and Amazon account credentials.

### Changed

- Refined the installed-games-first library, controller navigation, list and grid surfaces, downloads, driver settings, container configuration, and launch UI.
- Redesigned the sign-in and onboarding experience, including a dual-screen login layout and clearer privacy-policy access.
- Improved container storage management with visible on-disk paths and copy-to-clipboard support.
- Reworked console typography and shared navigation surfaces; localized new driver and download flows across all supported locales.
- Updated the README with the current console-shell direction, project lineage, data providers, and the 0.2 feature set.

### Fixed

- Prevented a visible black flash when Steam connects after the app starts in offline mode.
- Repaired migrated containers whose legacy Wine/Proton symbolic links point to removed runtime paths.
- Improved DX wrapper manifest parsing, x86_64 input DLL extraction, and native Android launch handling.
- Fixed secondary-display dialogs, focus handoffs, container configuration, quick-menu, crash-dialog, and controller-input edge cases.
