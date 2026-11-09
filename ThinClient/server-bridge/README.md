# Matching server protocol source

This is the `nh_thin_bridge/src` snapshot used by the Android client, rechecked unchanged on 2026-11-09. It is retained for protocol traceability and is excluded from the iOS client JAR/IPA. Build it in the server project with Java 17 and the matching Paper 1.20.1 API. The newer Forge tablet social channel is supplied by the server's separate `newhorizon:tablet_chat` implementation; this Paper bridge alone does not provide it. No server files, plugins or services are modified by the iOS build.
