# Android import — 2026-10-12 (iOS 1.2)

Source: `C:\Users\adamo\Desktop\PojavLauncher`. This checkout has an empty `.git` directory, so the import is identified by content fingerprints rather than an invented commit ID. The Android tree was read without modification.

Fingerprints are SHA-256 over UTF-8 lines `relative/path:lowercase-file-sha256`, sorted by full source path and joined with LF (no final LF).

| Android source | Files | Tree SHA-256 |
| --- | ---: | --- |
| `nh_thin_client/src` | 3228 | `53c25013fbb691366a2a2c9e1771daf848bfcc634df1c397303acefba3a2c7ff` |
| `nh_client_patcher/src/main/java` | 16 | `6141a733a6d167c45e0a2582d9c3fca79963ddab88ce7da353700aed3a6a6886` |
| `nh_thin_bridge/src` | 3 | `8ea992c1cfc88039ee1f7d97c22eb957cb1cf968ceb94f425e92697f1cfea416` |
| `app_pojavlauncher/src/main/assets/wd_display` | 4 | `df7ef284f049f0369df7fa2c023ff05aa23a755d0bf2f0bab3864de11a076e17` |
| `app_pojavlauncher/src/main/assets/minepad` | 10 | `c3b9c30066e2ef8718b33ed662cc1faaaa741e32652f49d4e5cf3910e602315e` |
| `app_pojavlauncher/src/main/assets/vanilla_item_icons` | 677 | `ef11014d144eb2a67fbadd680094661a3fddeb6d7703a4a66a2b80db28d3f162` |
| `app_pojavlauncher/src/main/assets/inventory_lang` | 4 | `20ca6deadfa6e68e8b26d1d4f7e25fba0a6ed137ef60c7d1c483bc560a4e7710` |
| `app_pojavlauncher/src/bundledMods/newhorizonLowMemoryEngine` | 10 | `faf5996b9b474af098a4ecc85f89618990e3351f67ad6f95ebc4ff02da6fe245` |

The original creative inventory catalog has SHA-256 `cfd4854e0ee3c8e5f31bcdd5fbfae1c484691345c39511c2775dc5138f8e26a5`. Its per-variant protocol IDs, stack limits, category membership and NBT are retained.

## Platform adaptations

- `ThinClientMain` loads the Amethyst executable on iOS instead of Android's `libpojavexec.so`.
- `LtwScene` omits Android's new one-pixel `glReadPixels` diagnostic. Even diagnostic browser pixels remain on the GPU, as required for this port.
- `GeckoNativeBridge` keeps the Android JNI signatures; iOS exports them through the existing IOSurface/ANGLE bridge. CPU browser transport remains unavailable. `MinePadResources` serves the original tablet resources from the JAR over loopback HTTP and canonicalizes URLs back to `mod://` for server persistence.
- The standalone runtime uses UIKit inventory/chat/HUD controls and Reynard GeckoView tablet chrome. World geometry, models, textures, animation, physics and audio remain in the imported Java/LWJGL core. The UI is adapted to iOS and is not pixel-identical to Android's Canvas widgets.
- Thin display page scripts, navigation locking, laser-gated link capture, tab events, embedded-frame input and Unicode chat are connected to Reynard. Browser frames still enter LTW through the shared GPU texture slots.
- Forge restores verified pristine launcher-owned artifacts, including patch markers from previous iOS profile versions. The new patcher remains bundled for source parity and explicit patch workflows.
- The session/low-memory mods and MCEF have not changed in this Android snapshot; the existing iOS superflat creator is retained. WebDisplays is updated from Android (SHA-256 `1099228cfcb4e7ce93101aa09ae6b2fef918036541d1c12b5f2180b912a77414`).
- `InventoryLanguage`, `ThinItemTag`, `ThinContainerTag`, `MinePadDomainList` and `MinePadNavigationPolicy` are copied unchanged from Android's platform-independent UI helpers. `IOSInventoryPresentation` projects names/lore without modifying the authoritative stack/NBT; `IOSMinePadPolicy` loads the same bundled rules. Reynard checks top-level/subframe requests and popups through a bounded UTF-8 JNI callback, failing closed if the policy cannot run.
- UIKit now accepts variable-sized chest menus, exposes a persisted pause/options menu and localized item names, and uses the original generated item icons. Its layout is still native, not Android Canvas. Android-only launcher cosmetics and keyboard/touch-editor screens are not ported verbatim; Amethyst supplies those native screens.
- Android's ARM64 LTW binary is still dated 2026-06-16; there is no new Android LTW source tree in this snapshot. The existing iOS LTW source port and Apple compiler fixes remain in use.
- Android activities, GeckoView Android binaries, Linux/Termux runtimes, Android GLES-layer hooks, APKs and diagnostic checkpoints are platform-specific and are not included in the IPA. Their application behavior uses the existing Amethyst/JVM/Reynard paths plus the new UIKit adapters.

## Verification boundary

The imported Java core self-tests exercise bounded memory, protocol parsing, chunk streaming, meshing/lighting, exact resource catalogs, entity rigs/animation, swimming, combat, inventory, chat, riding, MinePad, audio math/catalogs, particles and atmosphere. The additional iOS resource test exercises the real loopback HTTP endpoint and resource allowlist. Patcher self-tests run separately.

Windows cannot compile UIKit/GeckoView or package the IPA. Codemagic runs the Java tests and an early UIKit type-check; a successful Xcode build and device smoke test remain necessary before declaring iOS runtime parity.

`Scripts/check-android-import.ps1 -AndroidRoot <checkout>` checks that all 3,224 unadapted thin-core files/resources still match the source byte for byte. The four declared shared-file adaptations are `ThinClientMain`, `GeckoNativeBridge`, `ThinClientSelfTest` and `LtwScene` (GPU-only diagnostic change); the loopback resource handler/test, UI projection/policy helpers and original Android UI assets are additional files. Java 8 compilation and the expanded core tests pass locally. The original Android policy test also passes 346,609 checks over 84,133 domains. JNI helper syntax is checked against this repository's `jni.h`; UIKit/GeckoView compilation still requires the macOS pipeline.
