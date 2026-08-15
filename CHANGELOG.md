# Changelog

All notable changes to Gameplay are documented here.

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
