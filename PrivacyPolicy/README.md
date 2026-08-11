# Privacy Policy for Gameplay
<sub>Last Updated: August 6, 2026</sub>

Gameplay is an unofficial client application that lets you access your libraries from supported PC game stores and play your owned games on your device. This policy describes what data Gameplay collects, stores, and shares, and the choices you have.

## Data Stored on Your Device

**Store credentials.** When you sign in to Steam, GOG, Epic Games, or Amazon, your session credentials are stored only on your device:

- **Steam** tokens are encrypted with the Android Keystore.
- **GOG, Epic, and Amazon** OAuth tokens are stored in the app's private files directory.
- Steam user ID, Steam ID, and username are stored in app preferences.

We do not store your store passwords, and we do not transmit your credentials to our own servers.

**Local app data.** Gameplay keeps the following on your device: game container configurations, library and play-history data, downloaded game metadata, cached recommendations, sync timestamps, and crash logs. Android app backup is disabled, so this data is not included in Android's cloud backup.

## Data Sent to Our Servers

Gameplay operates its own service (api.gamenative.app) to improve game compatibility and to share working settings across the community. It receives data only in the following cases:

**Optional game feedback.** After you exit a game, Gameplay may show a feedback dialog. If you choose to submit a rating, tags, and/or notes, the app sends to our server: the game name, your device model and manufacturer, GPU name, SoC name, Android version, app version, the game's container configuration, and session statistics (average FPS and session length). This happens only when you actively submit the feedback form.

**Compatibility and settings lookups.** To fetch community-proven settings and compatibility information, the app sends your device model, GPU name, and build type as part of the request.

**Hardware attestation.** When fetching recommended configurations, the app may verify the request with Android Key Attestation. This involves sending a nonce and the device's attestation certificate chain to our server. It is a hardware-level device identifier used to authenticate the request; it is not used to build a profile.

As with any internet service, the servers you connect to (including our own) see your IP address as part of ordinary network traffic.

We do not use any third-party analytics SDK, we do not track browsing or gameplay for advertising, and we do not sell your data.

## Cloud Saves

If cloud saves are enabled on the stores you use, Gameplay synchronizes your game save files with the corresponding store service:

- **Steam Cloud** — save files are automatically uploaded and downloaded.
- **GOG Cloud** — save files are synchronized with cloudstorage.gog.com.
- **Epic Cloud Saves** — save files are synchronized with Epic's cloud save service.
- Steam Workshop content is downloaded only; Gameplay does not upload Workshop content.

Uploaded save files are handled under each store's own privacy policy and terms of service.

## Crash and Error Logs

On a crash, Gameplay writes a local crash log to the app's external files directory. It includes device information, the game and container context, and the most recent logcat lines for the app's process. Logcat may contain URLs and debug output; Gameplay redacts access tokens and secrets before saving. Crash logs are stored only on your device, are never uploaded, and are rotated to a single recent file.

## Game Store Integrations

Gameplay acts as an interface to the game stores you choose to use (Steam, GOG, Epic Games, Amazon). Your device communicates directly with each store's servers to authenticate your account, list your library, download games, and sync saves. Any data those stores collect is governed by their own privacy policies and terms of service.

## Game Recommendations

Gameplay can suggest games you might like from GOG.com, based on the games you already play. We do not send any of your game or account information to GOG unless you opt in by accepting the in-app disclosure prompt. You can turn the feature off at any time in **Settings → Interface → Show game recommendations**.

Once you have opted in:

- The app looks at which games you play most and most recently, and shares the corresponding game identifiers with GOG's recommendation service.
- If you are signed in to GOG, your GOG account identifier is included, so recommendations can be tailored and games you already own can be excluded.
- These identifiers are sent directly to GOG's recommendation, catalog, and games-database services. GOG's handling of them is governed by GOG's own privacy policy.

Recommended game links use an affiliate program (Commission Junction). If you follow one of these links, the affiliate network may record the click for attribution, and Gameplay may earn a commission on any resulting purchase. This does not change the price you pay.

Turning recommendations off stops all of the above.

## Third-Party Services and Content

Gameplay uses the following third-party services to provide game content:

- **Downloads and mirrors** — game files, Wine/Proton/Ubuntu components, and other assets are served from Gameplay's download infrastructure and content-delivery network.
- **GitHub** — component manifests and update checks use GitHub's API; app updates are downloaded from the project's official GitHub releases.
- **SteamGridDB** — optional custom artwork, enabled only when an API key is configured.
- **HowLongToBeat** — game length information.
- **Nexus Mods** — mod browsing, downloading, and installation; authorization uses a short-lived token passed via a deep link.
- **YouTube** — embedded game trailer playback.

## Permissions

Gameplay requests the following permissions for the purposes described:

- **Internet / network state** — store access, downloads, and updates.
- **Storage (Android 9 and earlier)** — installing and running game files.
- **Microphone** — in-game microphone use where a game requests it.
- **Foreground service** — keeping downloads and syncs running.
- **Install packages** — installing downloaded app and game updates.
- **Notifications** — download and background task progress.
- **Read logs** — writing detailed crash logs.
- **Vibration** — controller feedback.

Gameplay does not request camera, location, or contacts access.

## Data Security

Store credentials are kept on your device and are not transmitted to our servers. Steam tokens are encrypted with the Android Keystore. GOG, Epic, and Amazon tokens are stored in the app's private files directory, which is not accessible to other apps. We recommend protecting your device with a screen lock.

## Your Rights and Data Deletion

You can clear all locally stored data at any time by:

- Logging out of the app,
- Clearing app data through your device settings, or
- Uninstalling the application.

For data held on our servers (for example, game feedback or attestation records), email support[at]gamenative[dot]app and we will action verified deletion requests.

## Meta Horizon (Quest) Store Version

**This section applies only to the version of Gameplay distributed through the Meta Horizon Store. The free, open-source build distributed via GitHub does not collect or use any of the data described in this section.**

On the Meta Horizon Store version, the app reads your Meta account ID and subscription/entitlement status to confirm access and unlock the app on that platform. This information is used only for verification, is processed on your device and through Meta's in-app purchases platform, and is not stored on our servers or shared or sold. Meta's handling of it is governed by Meta's privacy policy.

## Changes to Privacy Policy

We may update this privacy policy from time to time. Changes take effect when the updated policy is posted.

## Contact

For questions about this policy or Gameplay's privacy practices, contact support[at]gamenative[dot]app.

## Age Requirements

Users must be at least 13 years old to use Gameplay, in accordance with the supported stores' own age requirements.
