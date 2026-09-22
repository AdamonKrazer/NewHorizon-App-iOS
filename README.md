# New Horizon Launcher for iOS

Port of the New Horizon Android launcher to iOS/iPadOS, built on the Amethyst native launcher base. Version **1.1** imports the Android source snapshot from **2026-09-22**, including the independent New Horizon thin client alongside **Minecraft 1.20.1 + Forge 47.4.0**.

## Production contract

- Normal mode selects `1.20.1-forge-47.4.0`; missing Forge is installed from its official Maven repository. Launcher-owned patches from older releases are restored from verified pristine backups, matching Android's current normal-Forge route.
- **Settings → Java → Lightweight client** selects the imported Java/LWJGL runtime and protocol 763. Minecraft 1.20.1 metadata/assets are downloaded, but Minecraft/Forge classes and mod JARs are not loaded into that runtime.
- Configure **Lightweight client server** as `host:port`. The Android default `127.0.0.1:9055` is retained; it needs a tunnel on the iPhone itself. An Android/desktop loopback tunnel is not reachable from another device.
- The existing **Quick local test (superflat)** takes priority over lightweight mode. It creates/reopens the local Creative/Peaceful test world through Forge, including when the server is offline.
- LTW is the only exposed and accepted Minecraft renderer.
- Browser composition is GPU-only. A browser that requests CPU frames is rejected instead of silently falling back.
- Server authentication credentials are stored in the iOS Keychain and commands are sent through the in-game chat state machine.
- The low-memory profile mirrors Android's reduced video settings and bundled low-pressure mods.

The imported thin core includes bounded chunk streaming/mesh workers, vanilla block/item/entity resources, animation, swimming, riding, rails, fishing, combat, effects, weather/day-night, OpenAL audio, inventories, protocol chat and WebDisplays/MinePad state. UIKit presents the iOS inventory/creative catalog, hotbar, HUD, chat, respawn and tablet controls. It is a native presentation of the shared state, not an Android Canvas view embedded in iOS; its layout and styling differ from Android.

`ThinClient/server-bridge/src` records the matching Android server plugin source for protocol compatibility. It is not shipped in the IPA and this update does not deploy or restart any server.

## GPU architecture

| Producer | Transport | Consumer |
| --- | --- | --- |
| Minecraft/OpenGL | LTW core-to-ES translation | ANGLE/Metal |
| Gecko WebRender | `IOSurface` front buffer | MCEF shared ANGLE texture |

The Gecko bridge uses three consumer texture slots, EGL fences and a latest-frame-wins queue. It does not use `glReadPixels`, browser snapshots, bitmap copies or a WebKit fallback. The mandatory MCEF CPU JNI entry points remain ABI stubs that return failure; they are not a hidden rendering path.

## Imported components

- Android application behavior: local source at `C:\Users\adamo\Desktop\PojavLauncher`.
- LTW integration: [catsruledogs/Amethyst-iOS-25](https://github.com/catsruledogs/Amethyst-iOS-25), with the Android LTW source set reproduced in CMake.
- Browser: [minh-ton/reynard-browser](https://github.com/minh-ton/reynard-browser), pinned by the vendored source to commit `cc05dd...`.
- Gecko: Firefox tag `FIREFOX_154_0_RELEASE`, resolved to commit `032a9fc1ac0cc3209f7c142744ba2e40847c8086`.

The Firefox checkout under `Natives/external/Reynard/engine/firefox` is generated and intentionally ignored. The build script validates the pinned commit and applies every vendored Reynard/New Horizon patch idempotently.

## Build

The complete iOS build requires macOS, Xcode with the iPhoneOS SDK, Rust, Python 3, Git, CMake, JDK 8, `ldid` and the original Amethyst native dependencies.

```sh
# Build GeckoView plus the Reynard process helper.
make reynard

# Clean, build all launcher/JRE/browser components, assemble and package.
make all PLATFORM=2
```

`make all` deliberately runs `clean` before the build as an ordered step, so parallel Make execution cannot race a clean against Gecko/native/Java compilation. Reynard's first build downloads the pinned Firefox source and is substantially longer than an ordinary launcher rebuild.

The resulting IPA is written below `artifacts/`. JIT and increased-memory entitlements still depend on the chosen signing/install method, as with upstream Amethyst.

The Java build compiles `ThinClient` from source and runs its self-tests before Gecko. The Reynard script also parses the two iOS bridge files and type-checks the standalone UIKit interface before the Firefox build. Local Windows validation covers the Java core, patcher and JavaScript; final Xcode compilation, IPA installation, GPU presentation, audio and touch behavior require the macOS/iOS build and a device.

## Layout

- `JavaApp/src/launcher/com/newhorizon`: Android-derived client patcher and bundled-mod installer.
- `Natives/NewHorizonClient.*`: production Forge validation and cleanup of obsolete lite metadata.
- `Natives/NewHorizonServerAuth.*`: Keychain-backed Login/Register flow.
- `Natives/gecko_mcef_jni.c`: IOSurface-to-ANGLE MCEF bridge.
- `Natives/external/Reynard/browser/GeckoView/NewHorizonMCEFBridge.swift`: Gecko session and native messaging integration.
- `Natives/external/LTW`: LTW sources imported for the native iOS build.
- `Scripts/build-reynard.sh`: reproducible Gecko/Reynard build bootstrap.
- `ThinClient/src`: imported Android thin runtime and resources, with iOS bootstrap/resource-route adaptations.
- `Natives/external/Reynard/browser/GeckoView/NewHorizonThinUI.swift`: UIKit presentation and input events for the thin runtime.
- `ThinClient/ANDROID-SNAPSHOT.md`: source fingerprints and platform adaptation notes.
- `Scripts/update-android-mods.ps1`: refreshes bundled Android mod classes while retaining the iOS superflat creator.

## Licensing and upstream credits

This port retains the licenses and notices of Amethyst/PojavLauncher and their dependencies. Important additions include:

- [Reynard Browser](https://github.com/minh-ton/reynard-browser): GNU GPLv3.
- [Mozilla Firefox/Gecko](https://www.mozilla.org/firefox/): Mozilla Public License 2.0 and applicable third-party notices.
- [LTW](https://github.com/PojavLauncherTeam/LTW): GNU LGPLv3.
- [Amethyst iOS](https://github.com/AngelAuraMC/Amethyst-iOS) and [Boardwalk](https://github.com/zhuowei/Boardwalk): original launcher foundations and their respective licenses.
- [ANGLE](https://chromium.googlesource.com/angle/angle/): BSD-style license.

Review the vendored `LICENSE` files before redistribution, especially when distributing Gecko/Reynard binaries and corresponding source.
