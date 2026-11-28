# Gameplay

**Play your PC games on Android.**

Turn your Android phone or handheld into a console for the games you already own. Browse Steam, GOG, Epic Games Store, Amazon Games and itch.io alongside your local Windows installs, then launch compatible games directly on the device. Gameplay is free, open source and designed for gamepads and touch.

[**Download Gameplay for Android**](https://github.com/intercontinentalmassacre/Gameplay/releases/latest/download/Gameplay-modern-release.apk) · [Release notes and checksums](https://github.com/intercontinentalmassacre/Gameplay/releases/latest) · [Report an issue](https://github.com/intercontinentalmassacre/Gameplay/issues)

<img width="834" height="887" alt="{3D316416-197D-442F-A4F2-363EDE8EA080}" src="https://github.com/user-attachments/assets/9dda553b-d0d0-40d0-80a0-05c2506df88f" />

## New in Gameplay 0.7

- **Browse itch.io from your handheld.** Open **System → itch.io Store** to search free games or browse by popularity, release date or rating. Use the shoulder buttons to switch categories and **Load more** to continue. On dual-screen devices the companion shows the selected game's artwork.
- **Find the games you own.** Sign in to itch.io and open the **itch.io** library tab. Gameplay syncs your games, including uninstalled titles, when you return to the library. **My games** in the store shows the same account collection and excludes asset packs.
- **Play browser games and Game Boy cartridges.** Install supported HTML5 builds and Game Boy or Game Boy Color cartridges, then launch them with **Play** on the game card. PICO-8 and the bundled Game Boy player support physical controls. The cartridge player also offers an on-screen D-pad, A, B, Start and Select when playing by touch.
- **Choose how you touch a Windows game.** Enable **Touchscreen Mode** in the container settings or the in-game controller menu to click at the point you touch. On dual-screen handhelds, open the companion touchpad when you need it.

The HTML5 integration includes patches from [GameNative](https://github.com/utkarshdalal/GameNative). The local cartridge player uses [binjgb](https://github.com/binji/binjgb).

## Bring your library with you

- **Your stores and your own files in one library.** Sync connected storefronts or import a Windows executable. Run an EXE or MSI installer in a dedicated container, then add the installed game to your library. You can also import ARM64 Type 2 AppImages and launch them in a shared Linux runtime.
- **Designed for handhelds.** Browse with a gamepad or touch, open a quick menu mid-game and customize inputs per title. Pair left and right Joy-Con as one player or use Xbox-compatible, DualSense or Steam controllers. On dual-screen devices, keep downloads, settings or game details on the companion display.
- **Tune each game independently.** Pick a Wine or Proton runtime, CPU translation and GPU driver, then set up DXVK, VKD3D-Proton or your preferred graphics path. Containers keep game settings separate and reuse downloaded runtime components.
- **Pick up where you left off.** Use achievements and Steam Cloud for supported titles, track play sessions and check live FPS in the in-game HUD. Make the console yours with built-in themes or [importable themes](docs/themes).

Library search accepts partial words in any order, so `automata nier` finds *NieR:Automata*. For Windows games, open the container's General settings to bookmark a working graphics, audio and performance configuration before experimenting. Restore it from the same page when using the same Wine runtime.

Open **Recent** to find games played on this device, newest first. You can also add games from different stores to **Favorites** from their cards. Choose **Play next** on a favorite to keep it first in that list. Select its card and press **Play** whenever you’re ready.

After a Windows or HTML5 game session ends, its card shows the last session length and total time recorded on this device. Existing library history keeps its last-played dates. Time tracking begins with sessions played on the updated app.

In **Settings → Controls → Quick menu shortcut**, choose a controller button to hold during Windows or HTML5 games. A short press still goes to the game. The shortcut starts disabled. In an HTML5 game, you can also open the menu with Android Back to resume or exit.

Windows startup shows the current stage. If the runtime takes more than 30 seconds to show a game window, you can dismiss the startup screen and inspect the running session.

## What's new in 0.4

- **Choose X11 or Gamescope per game.** Gamescope brings adjustable output resolution, FSR, NIS and SGSR scaling, plus color filters and ReShade effects. HDR is in beta. Experimental Adreno presentation and frame pacing are opt-in. See the [Gamescope notes](docs/gamescope-experimental.md).
- **More graphics options for Adreno and Mali.** The catalog adds newer Turnip and PanVK builds, Proton 11 builds for x86-64 and ARM64EC, DXVK 3.1.1 and VKD3D 3.1. Start with **Mali DX9 System** or the **Neo Pocket System DX9** preset under **Options → Container → Game profiles**. The Pocket preset is tuned for 3 GB devices and targets 640×480 at 30 FPS.
- **Better Steam installs and cloud saves.** Cancelling a Steam update preserves the installed game. The release improves shared-library and executable selection, and lets you decide which copy to keep when local and cloud saves conflict.
- **More control while you play.** The handheld interface and companion display get clearer game and controller status. Supported kernels can reclaim memory from paused Windows games and resume them automatically. Wine 11 gains updated DirectAudio packaging and a GNAudio low-latency option.

See the [changelog](CHANGELOG.md) for device results and the full list of changes.

## What’s new in 0.4.1

- Choose a compatibility preset and DXVK version while installing supported Steam and Epic games. Eligible Wine containers can share a base for common components.
- Customize physical-controller bindings or try the optional local Vulkan shader cache, which records pipelines for reuse on later launches.
- Import local mods and manage their profiles and file priority. Online Nexus Mods access is currently disabled.
- Export a per-game support bundle from the game menu to share launch diagnostics.

## Get started

1. Install the [latest modern APK](https://github.com/intercontinentalmassacre/Gameplay/releases/latest/download/Gameplay-modern-release.apk) on an ARM64 device running Android 10 or newer.
2. Connect a storefront, or open **Add game** and select **Import EXE or AppImage** for a local executable. Use the installer option for an EXE or MSI setup package.
3. Install or launch the game from your library. Allow extra storage for game files and the shared runtime downloaded during setup.

Games run on your device through Windows compatibility tools. Use your own game files and accounts. Compatibility varies by game, Android version, GPU and driver. Vulkan-capable hardware is recommended for modern DirectX titles. Anti-cheat, kernel drivers and some DRM can prevent games from running. The local importer also supports ISO9660 images containing a Windows installer or portable executable. Standalone DOS titles need a separate workflow.

To copy a portable Windows game from your PC, choose **Add game → Receive over Wi-Fi** in Gameplay. Open the displayed address in your PC browser on the same local network, enter the six-digit code and select the game folder. Keep the receiver open until the transfer finishes. If Gameplay finds multiple executables, choose the one you normally run, then open the game from your library with **Play**.

To send a newer build, open the received portable game's card and choose **Update over Wi-Fi**. Select the updated folder with the same name on your PC. Gameplay compares file contents, transfers new or changed files and keeps the existing game card and container. Files present only on the handheld stay in place. If both copies of a file have changed since the last transfer, Gameplay reports the conflict before applying the update.

For a custom Windows game, open its save backups and enter the folder containing its saves, such as `Saves` inside the game directory or a subfolder of `C:/` in its Wine container. You can restore a snapshot on the handheld or export its files as a ZIP for your PC.

See [per-game compatibility notes](docs/game-compatibility.md) for specific fixes and optional graphics settings.

The source also provides a `legacy` build flavor for Android 8 and newer. The APK linked above is the signed `modern` release.

## Building from source

Use a current Android Studio installation with its bundled JDK. Clone submodules when obtaining a fresh checkout:

```sh
git clone --recurse-submodules https://github.com/intercontinentalmassacre/Gameplay.git
cd Gameplay
```

### Windows

Prepare or copy the embedded Buran payload from a Linux x86-64 build before building modern variants on Windows. [Buran build instructions](buran/README.md)

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat :app:assembleModernDebug --no-daemon
```

### Linux

Modern variants build the embedded Buran payload on Linux x86-64. Install the additional runtime build tools before running Gradle. [Buran build instructions](buran/README.md)

```sh
./gradlew :app:assembleModernDebug --no-daemon
```

The resulting APK is written to:

```text
app/build/outputs/apk/modern/debug/app-modern-debug.apk
```

The build check runs unit tests and assembles both `modernDebug` and `legacyDebug`. The `modernXr` and `legacyXr` flavors are experimental.

### macOS

For modern variants, provide the same prepared Buran payload described in the Windows section, then use the Linux Gradle command.

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

Push the version tag (for example, `v0.7`) on a commit from `working-builds` to build the signed APK. On Windows, use `v` followed by `versionName`. For 0.7, run `powershell -ExecutionPolicy Bypass -File .\scripts\release-preflight.ps1 -Tag v0.7` locally before publishing. It requires local `app/keystores/keystore.properties`, which must never be committed.

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
- that every newly added string resource exists in all supported locales.

For runtime changes, verify both a storefront title and a locally imported executable or installer where applicable. Container and database changes must preserve existing installations.

Before submitting code, build at least the primary variant:

```sh
./gradlew :app:assembleModernDebug --no-daemon
```

New bundled binaries must include their applicable license, notice, and reproducible source or source-offer information. Do not remove upstream copyright statements.

## Contributing

Issues and focused pull requests are welcome. Describe the device, Android version, SoC/GPU, selected driver, Wine/Proton version, graphics layer, and the exact stage that fails when reporting compatibility problems. Do not attach copyrighted games, credentials, authentication tokens, or private logs.

Large interface changes should preserve Gameplay's controller-first, touch-compatible product direction and document user-visible behavior in the pull request.

## Credits and thanks

Gameplay stands on the work of many people and projects. This list is not exhaustive; the full per-component notice set is in [THIRD_PARTY_NOTICES](THIRD_PARTY_NOTICES).

### Upstream lineage

Gameplay is a fork of [GameNative](https://github.com/utkarshdalal/GameNative) by Utkarsh Dalal, the Android shell that brought Steam, GOG, Epic, and Amazon libraries to Wine/Proton containers on ARM64 devices.

GameNative is itself a fork of [Pluvia](https://github.com/utkarshdalal/Pluvia) (the original project name), which was the first Android client to bundle a working Wine/Proton container for Steam.

Both Pluvia and GameNative build on the [Winlator](https://github.com/lutris/winlator) Wine-on-Android project. Gameplay inherits their container, XServer, input-controls, graphics-translation, and Steamworks code, and ships its own theming, library, controller, and local-installer layers on top.

### Bundled native components

These projects are vendored under `app/src/main/cpp/` and are loaded by the runtime. Each has its own license and copyright in the matching subdirectory.

- [Wine](https://www.winehq.org/) and [Proton](https://github.com/ValveSoftware/Proton) (Valve) — Windows compatibility layer and game runtime.
- [DXVK](https://github.com/doitsujin/dxvk) and [VKD3D-Proton](https://github.com/HansKristian-Work/vkd3d-proton) — Vulkan-based translation for Direct3D 9/10/11/12.
- [virglrenderer](https://gitlab.freedesktop.org/mesa/virglrenderer) (Mesa / freedesktop.org) — guest-to-host GPU translation used by the embedded X server.
- [adrenotools](https://github.com/darksylinc/adrenotools) by Robert "darksylinc" Day — loadable Adreno GPU driver wrapper.
- [lsfg-vk](https://github.com/PancakeTAS/lsfg-vk) by PancakeTAS — Lossless Scaling frame-generation Vulkan layer.
- [MoltenVK](https://github.com/KhronosGroup/MoltenVK) (Khronos) — Vulkan-over-Metal shim used on supported paths.

### Storefronts and data providers

Gameplay talks to these services for library sync, ownership, and metadata. None are affiliated with this project; their data, branding, and access rules belong to the respective providers.

- [Steam](https://store.steampowered.com/) and [Steamworks](https://partner.steamgames.com/doc/sdk) (Valve) — authentication, library, cloud saves, and store metadata.
- [GOG](https://www.gog.com/) (CD Projekt) — authentication, library, and cloud saves.
- [Epic Games Store](https://store.epicgames.com/) (Epic Games) — authentication and library.
- [Amazon Games](https://gaming.amazon.com/) (Amazon) — authentication and library.
- [SteamGridDB](https://www.steamgriddb.com/) — community-contributed grid, hero, logo, and icon artwork used for library enrichment. A user-supplied API key in `local.properties` activates this integration.
- [NexusMods](https://www.nexusmods.com/) — mod hosting and Nexus Mod Manager download flow. Online Nexus access is currently disabled in the working build. The local mod manager remains available.
- [PCGamingWiki](https://www.pcgamingwiki.com/) — community-maintained Windows-game fix database used to seed container configuration defaults.

### Compatibility and runtime data

- [ProtonDB](https://www.protondb.com/) (community, powered by Valve compatibility reports) is the de-facto reference for Linux/Wine game compatibility; Gameplay's community-config filter and per-game best-config service are shaped to interoperate with its data conventions.
- [WineHQ AppDB](https://appdb.winehq.org/) and the [Wine staging](https://wiki.winehq.org/Wine-Staging) project for runtime patches.

### Development tools and assistance

This project uses AI-generated code for design and theme engine work. The interface vocabulary, theme tokens, several settings and dialog flows, and refactors in this repository were produced with AI assistance (Anthropic Claude and OpenAI GPT, via opencode and Claude Code). Reviewers are listed in the commit log per change.

## License, attribution, and trademarks

Gameplay is distributed under the [GNU General Public License version 3](LICENSE). If you distribute a modified APK or other binary, you must satisfy the GPL-3.0 requirements, including providing the corresponding source under the same license.

GameNative, Pluvia, Winlator, and their contributors retain copyright in their original work. Gameplay's fork attribution is recorded in [NOTICE](NOTICE), and per-component licenses and source offers are recorded in [THIRD_PARTY_NOTICES](THIRD_PARTY_NOTICES) and beside relevant assets.

All trademarks, store names, game titles, and screenshots remain the property of their respective owners. Gameplay is not affiliated with Valve, CD Projekt, Epic Games, Amazon, or any storefront, publisher, or modding community listed above.

Gameplay is not affiliated with or endorsed by Valve, Microsoft, Sony, Epic Games, GOG, Amazon, CodeWeavers, or the Wine project. Steam, Xbox, PlayStation, Windows, and other names and marks belong to their respective owners.
