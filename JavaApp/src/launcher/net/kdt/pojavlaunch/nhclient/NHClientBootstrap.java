package net.kdt.pojavlaunch.nhclient;

import android.util.Log;

import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.newhorizon.clientpatcher.NhClientPatcher;
import com.newhorizon.clientpatcher.PatchProfile;
import com.newhorizon.clientpatcher.PatchResult;

import net.kdt.pojavlaunch.Tools;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Prepares a locally-derived New Horizon client before the JVM starts.
 *
 * Forge resolves Minecraft from a fixed Maven path under libraryDirectory, so
 * the derived JAR must occupy that path. A pristine adjacent backup is kept and
 * every replacement is prepared and verified before the live artifact changes.
 */
public final class NHClientBootstrap {
    private static final String TAG = "NHClientBootstrap";
    public static final String LITE_VERSION_ID = "1.20.1-newhorizon-lite";
    public static final String LITE_MARKER = ".newhorizon-lite";
    private static final String DISABLE_MARKER = "nh-client-patch.disabled";
    private static final String SRG_RELATIVE_PATH =
            "net/minecraft/client/1.20.1-20230612.114412/"
                    + "client-1.20.1-20230612.114412-srg.jar";
    private static final String EXTRA_RELATIVE_PATH =
            "net/minecraft/client/1.20.1-20230612.114412/"
                    + "client-1.20.1-20230612.114412-extra.jar";
    private static final String EXTRA_SHA1 =
            "8c5a95cbce940cfdb304376ae9fea47968d02587";
    private static final long EXTRA_SIZE = 10_436_626L;
    private static final String FORGE_CLIENT_RELATIVE_PATH =
            "net/minecraftforge/forge/1.20.1-47.4.0/"
                    + "forge-1.20.1-47.4.0-client.jar";
    private static final String BACKUP_SUFFIX = ".newhorizon-original";
    private static final String IMMEDIATELY_FAST_CONFIG =
            "config/immediatelyfast.json";
    private static final int COPY_BUFFER_SIZE = 32 * 1024;
    private static final PatchProfile MINECRAFT_PROFILE = PatchProfile.minecraft1201Minimal();
    private static final PatchProfile FORGE_PROFILE = PatchProfile.forge1201MinimalSound();

    private NHClientBootstrap() {
    }

    public static void prepareRuntimeClient() {
        File disableMarker = new File(Tools.DIR_GAME_HOME, DISABLE_MARKER);
        try {
            applyResourceLoadingPolicy();
        } catch (IOException exception) {
            Log.e(TAG, "NH resource policy failed; continuing with client preparation", exception);
        }
        prepareArtifact(new File(Tools.DIR_HOME_LIBRARY, SRG_RELATIVE_PATH),
                MINECRAFT_PROFILE, disableMarker, "Minecraft SRG client");
        prepareArtifact(new File(Tools.DIR_HOME_LIBRARY, FORGE_CLIENT_RELATIVE_PATH),
                FORGE_PROFILE, disableMarker, "Forge client overlay");
    }

    /**
     * Restores the pinned, pristine Minecraft/Forge artifacts for an ordinary
     * Forge launch.  The ultra-light patcher is intentionally reversible: its
     * adjacent backups remain the source of truth and the next thin launch can
     * derive the compact artifacts again.
     */
    public static void prepareVanillaRuntimeClient() {
        File reason = new File(Tools.DIR_GAME_HOME, "normal-forge-runtime");
        restorePristineClient(
                new File(Tools.DIR_HOME_LIBRARY, SRG_RELATIVE_PATH),
                new File(Tools.DIR_HOME_LIBRARY, SRG_RELATIVE_PATH + BACKUP_SUFFIX),
                reason, MINECRAFT_PROFILE, "Minecraft SRG client");
        restorePristineClient(
                new File(Tools.DIR_HOME_LIBRARY, FORGE_CLIENT_RELATIVE_PATH),
                new File(Tools.DIR_HOME_LIBRARY, FORGE_CLIENT_RELATIVE_PATH + BACKUP_SUFFIX),
                reason, FORGE_PROFILE, "Forge client overlay");
    }

    /**
     * Makes the lightweight SRG client visible to Pojav's normal version
     * resolver. A complete copy of Mojang's 1.20.1 metadata is used so the
     * normal game/version arguments are never lost during inheritance. The
     * client download section is removed because the locally-derived SRG JAR
     * is refreshed and verified immediately before JVM launch.
     */
    public static boolean prepareLiteVersionMetadata() {
        File versionDirectory = new File(Tools.DIR_HOME_VERSION, LITE_VERSION_ID);
        File versionJson = new File(versionDirectory, LITE_VERSION_ID + ".json");
        try {
            File parentJson = new File(new File(Tools.DIR_HOME_VERSION, "1.20.1"),
                    "1.20.1.json");
            if (!parentJson.isFile()) {
                throw new IOException("Minecraft 1.20.1 metadata is unavailable: "
                        + parentJson.getAbsolutePath());
            }
            JsonObject liteMetadata;
            try (Reader reader = new InputStreamReader(
                    new FileInputStream(parentJson), StandardCharsets.UTF_8)) {
                JsonElement parsed = JsonParser.parseReader(reader);
                if (!parsed.isJsonObject()) {
                    throw new IOException("Minecraft 1.20.1 metadata root is not an object");
                }
                liteMetadata = parsed.getAsJsonObject();
            } catch (RuntimeException exception) {
                throw new IOException("Could not parse Minecraft 1.20.1 metadata", exception);
            }
            liteMetadata.addProperty("id", LITE_VERSION_ID);
            liteMetadata.remove("inheritsFrom");
            liteMetadata.remove("downloads");
            JsonArray libraries = liteMetadata.getAsJsonArray("libraries");
            if (libraries == null) {
                throw new IOException("Minecraft 1.20.1 metadata has no libraries array");
            }
            JsonObject extraArtifact = new JsonObject();
            extraArtifact.addProperty("path", EXTRA_RELATIVE_PATH);
            extraArtifact.addProperty("sha1", EXTRA_SHA1);
            extraArtifact.addProperty("size", EXTRA_SIZE);
            JsonObject extraDownloads = new JsonObject();
            extraDownloads.add("artifact", extraArtifact);
            JsonObject extraLibrary = new JsonObject();
            extraLibrary.addProperty("name",
                    "net.minecraft:client:1.20.1-20230612.114412:extra");
            extraLibrary.add("downloads", extraDownloads);
            libraries.add(extraLibrary);
            byte[] expected = Tools.GLOBAL_GSON.toJson(liteMetadata)
                    .getBytes(StandardCharsets.UTF_8);
            if (!versionDirectory.isDirectory() && !versionDirectory.mkdirs()) {
                throw new IOException("Could not create lite version directory");
            }
            if (!versionJson.isFile()
                    || !java.util.Arrays.equals(expected, Files.readAllBytes(versionJson.toPath()))) {
                File temporary = new File(versionJson.getAbsolutePath() + ".tmp");
                try (OutputStream output = new FileOutputStream(temporary)) {
                    output.write(expected);
                }
                replace(temporary, versionJson);
            }
            Log.i(TAG, "Lite version metadata ready path=" + versionJson.getAbsolutePath());
            return true;
        } catch (IOException exception) {
            Log.e(TAG, "Could not prepare lite version metadata", exception);
            return false;
        }
    }

    /** Refreshes the derived version with the already-patched SRG client. */
    public static boolean prepareLiteClientJar() {
        prepareRuntimeClient();
        File source = new File(Tools.DIR_HOME_LIBRARY, SRG_RELATIVE_PATH);
        File versionDirectory = new File(Tools.DIR_HOME_VERSION, LITE_VERSION_ID);
        File destination = new File(versionDirectory, LITE_VERSION_ID + ".jar");
        if (!source.isFile() || !prepareLiteVersionMetadata()) {
            Log.e(TAG, "Lite SRG source is unavailable path=" + source.getAbsolutePath());
            return false;
        }
        try {
            String sourceHash = NhClientPatcher.sha256(source);
            if (destination.isFile()
                    && sourceHash.equals(NhClientPatcher.sha256(destination))) {
                Log.i(TAG, "Lite SRG client already current sha256=" + sourceHash);
                return true;
            }
            File temporary = new File(destination.getAbsolutePath() + ".tmp");
            copy(source, temporary);
            if (!sourceHash.equals(NhClientPatcher.sha256(temporary))) {
                throw new IOException("Lite SRG copy verification failed");
            }
            replace(temporary, destination);
            Log.i(TAG, "Lite SRG client refreshed sha256=" + sourceHash
                    + " path=" + destination.getAbsolutePath());
            return true;
        } catch (IOException exception) {
            Log.e(TAG, "Could not prepare lite SRG client", exception);
            return false;
        }
    }

    private static void prepareArtifact(File runtimeClient, PatchProfile profile,
                                        File disableMarker, String label) {
        if (!runtimeClient.isFile()) {
            Log.i(TAG, label + " is not installed; skipping path="
                    + runtimeClient.getAbsolutePath());
            return;
        }
        File pristineBackup = new File(runtimeClient.getParentFile(),
                runtimeClient.getName() + BACKUP_SUFFIX);
        if (disableMarker.isFile()) {
            restorePristineClient(runtimeClient, pristineBackup, disableMarker, profile, label);
            return;
        }
        try {
            if (NhClientPatcher.isPatched(runtimeClient, profile, profile.inputSha256)) {
                Log.i(TAG, "Using installed NH " + label + " path="
                        + runtimeClient.getAbsolutePath());
                return;
            }
            String runtimeSha256 = NhClientPatcher.sha256(runtimeClient);
            if (profile.inputSha256.equals(runtimeSha256)) {
                preservePristineClient(runtimeClient, pristineBackup, profile, label);
            } else if (!isPristine(pristineBackup, profile)) {
                Log.w(TAG, label + " does not match the pinned pristine or NH build; "
                        + "leaving it unchanged sha256=" + runtimeSha256);
                return;
            }
            long startedMs = System.currentTimeMillis();
            PatchResult result = NhClientPatcher.patch(pristineBackup, runtimeClient, profile);
            Log.i(TAG, "NH client prepared artifact=" + label + " profile=" + profile.id
                    + " changedClasses=" + result.totalChangedClasses()
                    + " elapsedMs=" + (System.currentTimeMillis() - startedMs)
                    + " inputSha256=" + result.inputSha256
                    + " outputSha256=" + result.outputSha256
                    + " path=" + result.output.getAbsolutePath());
        } catch (IOException | RuntimeException exception) {
            Log.e(TAG, "NH " + label + " preparation failed; keeping current artifact",
                    exception);
        }
    }

    /**
     * Keeps font atlases at Minecraft's 256x256 size. ImmediatelyFast normally
     * expands every FontTexture to 2048x2048; on the tested Mali device seven
     * mostly-empty pages consumed roughly 112 MiB of graphics memory at boot.
     * The rest of ImmediatelyFast remains enabled.
     */
    private static void applyResourceLoadingPolicy() throws IOException {
        File config = new File(Tools.DIR_GAME_NEW, IMMEDIATELY_FAST_CONFIG);
        JsonObject root = new JsonObject();
        if (config.isFile()) {
            try (Reader reader = new InputStreamReader(
                    new FileInputStream(config), StandardCharsets.UTF_8)) {
                JsonElement parsed = JsonParser.parseReader(reader);
                if (!parsed.isJsonObject()) {
                    throw new IOException("ImmediatelyFast config root is not an object");
                }
                root = parsed.getAsJsonObject();
            } catch (RuntimeException exception) {
                throw new IOException("Could not parse ImmediatelyFast config", exception);
            }
        }

        JsonElement current = root.get("font_atlas_resizing");
        if (current != null && current.isJsonPrimitive()
                && current.getAsJsonPrimitive().isBoolean()
                && !current.getAsBoolean()) {
            Log.i(TAG, "NH resource policy already active: font_atlas_resizing=false");
            return;
        }

        root.addProperty("font_atlas_resizing", false);
        File temporary = new File(config.getAbsolutePath() + ".nh.tmp");
        File parent = temporary.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            throw new IOException("Could not create ImmediatelyFast config directory: " + parent);
        }
        try (Writer writer = new OutputStreamWriter(
                new FileOutputStream(temporary), StandardCharsets.UTF_8)) {
            Tools.GLOBAL_GSON.toJson(root, writer);
        }
        replace(temporary, config);
        Log.i(TAG, "NH resource policy applied: font_atlas_resizing=false; "
                + "other ImmediatelyFast features unchanged");
    }

    private static void preservePristineClient(File runtimeClient, File pristineBackup,
                                               PatchProfile profile, String label)
            throws IOException {
        if (isPristine(pristineBackup, profile)) {
            return;
        }
        File temporary = new File(pristineBackup.getAbsolutePath() + ".tmp");
        copy(runtimeClient, temporary);
        if (!profile.inputSha256.equals(NhClientPatcher.sha256(temporary))) {
            temporary.delete();
            throw new IOException("Pristine client backup verification failed");
        }
        replace(temporary, pristineBackup);
        Log.i(TAG, "Preserved pristine " + label + " path="
                + pristineBackup.getAbsolutePath());
    }

    private static void restorePristineClient(File runtimeClient, File pristineBackup,
                                              File disableMarker, PatchProfile profile,
                                              String label) {
        try {
            if (!isOwnedPatch(runtimeClient, profile.inputSha256)) {
                Log.i(TAG, "NH client patch disabled by " + disableMarker.getAbsolutePath()
                        + "; " + label + " is already unpatched");
                return;
            }
            if (!isPristine(pristineBackup, profile)) {
                Log.e(TAG, "Cannot restore disabled " + label
                        + ": pristine backup is missing");
                return;
            }
            File temporary = new File(runtimeClient.getAbsolutePath() + ".restore.tmp");
            copy(pristineBackup, temporary);
            replace(temporary, runtimeClient);
            Log.i(TAG, "Restored pristine " + label + " because "
                    + disableMarker.getAbsolutePath() + " exists");
        } catch (IOException exception) {
            Log.e(TAG, "Failed to restore pristine Forge SRG client", exception);
        }
    }

    private static boolean isOwnedPatch(File file, String pristineHash) throws IOException {
        if (!file.isFile()) return false;
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(file)) {
            java.util.zip.ZipEntry entry = zip.getEntry(NhClientPatcher.MARKER_ENTRY);
            if (entry == null) return false;
            java.util.Properties marker = new java.util.Properties();
            try (InputStream input = zip.getInputStream(entry)) { marker.load(input); }
            // Recognize earlier iOS patch profiles as well as the current Android one.
            return pristineHash.equals(marker.getProperty("input.sha256"));
        }
    }

    private static boolean isPristine(File file, PatchProfile profile) {
        if (!file.isFile()) {
            return false;
        }
        try {
            return profile.inputSha256.equals(NhClientPatcher.sha256(file));
        } catch (IOException exception) {
            return false;
        }
    }

    private static void copy(File source, File destination) throws IOException {
        File parent = destination.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            throw new IOException("Could not create client artifact directory: " + parent);
        }
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        try (InputStream input = new FileInputStream(source);
             OutputStream output = new FileOutputStream(destination)) {
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
        }
    }

    private static void replace(File temporary, File destination) throws IOException {
        if (destination.exists() && !destination.delete()) {
            throw new IOException("Could not replace client artifact: " + destination);
        }
        if (!temporary.renameTo(destination)) {
            copy(temporary, destination);
            if (!temporary.delete()) {
                Log.w(TAG, "Could not remove temporary client artifact " + temporary);
            }
        }
    }
}
