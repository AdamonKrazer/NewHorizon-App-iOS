# Android import — 2026-09-22

Source: `C:\Users\adamo\Desktop\PojavLauncher`. This checkout has an empty `.git` directory, so the import is identified by content fingerprints rather than an invented commit ID. The Android tree was read without modification.

Fingerprints are SHA-256 over UTF-8 lines `relative/path:lowercase-file-sha256`, sorted by full source path and joined with LF (no final LF).

| Android source | Files | Tree SHA-256 |
| --- | ---: | --- |
| `nh_thin_client/src` | 2922 | `749589180cd3ceca38622de1a2c90b9bc6159838e831bd921383a5e71ac41bb7` |
| `nh_client_patcher/src/main/java` | 16 | `6141a733a6d167c45e0a2582d9c3fca79963ddab88ce7da353700aed3a6a6886` |
| `nh_thin_bridge/src` | 2 | `e67708cac321d02bc1edb626a4e53846ac55bb8f2ef6d8e72d33eeffc4168fc8` |
| `app_pojavlauncher/src/main/assets/wd_display` | 3 | `9a4c94aaaa633813549643969e94189e851221710014127f460b950851df576d` |
| `app_pojavlauncher/src/main/assets/minepad` | 6 | `286acfb5543c2034013cb7507a126ead09372902838b4d44893b04af4d8ee486` |
| `app_pojavlauncher/src/bundledMods/newhorizonLowMemoryEngine` | 10 | `faf5996b9b474af098a4ecc85f89618990e3351f67ad6f95ebc4ff02da6fe245` |

The original creative inventory catalog has SHA-256 `cfd4854e0ee3c8e5f31bcdd5fbfae1c484691345c39511c2775dc5138f8e26a5`. Its per-variant protocol IDs, stack limits, category membership and NBT are retained.

## Platform adaptations

- `ThinClientMain` loads the Amethyst executable on iOS instead of Android's `libpojavexec.so`.
- `GeckoNativeBridge` keeps the Android JNI signatures; iOS exports them through the existing IOSurface/ANGLE bridge. CPU browser transport remains unavailable. `MinePadResources` serves the original tablet resources from the JAR over loopback HTTP and canonicalizes URLs back to `mod://` for server persistence.
- The standalone runtime uses UIKit inventory/chat/HUD controls and Reynard GeckoView tablet chrome. World geometry, models, textures, animation, physics and audio remain in the imported Java/LWJGL core. The UI is adapted to iOS and is not pixel-identical to Android's Canvas widgets.
- Thin display page scripts, navigation locking, laser-gated link capture, tab events, embedded-frame input and Unicode chat are connected to Reynard. Browser frames still enter LTW through the shared GPU texture slots.
- Forge restores verified pristine launcher-owned artifacts, including patch markers from previous iOS profile versions. The new patcher remains bundled for source parity and explicit patch workflows.
- Bundled session classes are refreshed from both Android memory variants while retaining the existing iOS `ClientEvents` class and mod metadata. The source difference is the iOS superflat creator. The low-memory engine JAR and source are refreshed from Android; MCEF and WebDisplays JARs are byte-identical to the previous iOS versions.
- Android's ARM64 LTW binary is still dated 2026-06-16; there is no new Android LTW source tree in this snapshot. The existing iOS LTW source port and Apple compiler fixes remain in use.
- Android activities, GeckoView Android binaries, Linux/Termux runtimes, Android GLES-layer hooks, APKs and diagnostic checkpoints are platform-specific and are not included in the IPA. Their application behavior uses the existing Amethyst/JVM/Reynard paths plus the new UIKit adapters.

## Verification boundary

The imported Java core self-tests exercise bounded memory, protocol parsing, chunk streaming, meshing/lighting, exact resource catalogs, entity rigs/animation, swimming, combat, inventory, chat, riding, MinePad, audio math/catalogs, particles and atmosphere. The additional iOS resource test exercises the real loopback HTTP endpoint and resource allowlist. Patcher self-tests run separately.

Windows cannot compile UIKit/GeckoView or package the IPA. Codemagic runs the Java tests and an early UIKit type-check; a successful Xcode build and device smoke test remain necessary before declaring iOS runtime parity.

`Scripts/check-android-import.ps1 -AndroidRoot <checkout>` checks that all 2,919 unadapted thin-core files/resources still match the source byte for byte. The three declared shared-file adaptations are `ThinClientMain`, `GeckoNativeBridge` and `ThinClientSelfTest`; the loopback resource handler/test and original Android UI assets are additional files.
