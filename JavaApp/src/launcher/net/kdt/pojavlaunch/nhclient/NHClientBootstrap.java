package net.kdt.pojavlaunch.nhclient;

import android.util.Log;

import com.google.gson.JsonElement;
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

/**
 * Prepares a locally-derived New Horizon client before the JVM starts.
 *
 * Forge resolves Minecraft from a fixed Maven path under libraryDirectory, so
 * the derived JAR must occupy that path. A pristine adjacent backup is kept and
 * every replacement is prepared and verified before the live artifact changes.
 */
public final class NHClientBootstrap {
    private static final String TAG = "NHClientBootstrap";
    private static final String DISABLE_MARKER = "nh-client-patch.disabled";
    private static final String SRG_RELATIVE_PATH =
            "net/minecraft/client/1.20.1-20230612.114412/"
                    + "client-1.20.1-20230612.114412-srg.jar";
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
            if (!NhClientPatcher.isPatched(runtimeClient, profile, profile.inputSha256)) {
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
