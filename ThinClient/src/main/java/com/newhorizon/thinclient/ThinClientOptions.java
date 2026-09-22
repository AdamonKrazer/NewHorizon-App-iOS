package com.newhorizon.thinclient;

import com.newhorizon.thinclient.protocol.SessionCredentials;

import java.util.UUID;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Reads the ordinary Minecraft launcher arguments without retaining the array. */
final class ThinClientOptions {
    final String host;
    final int port;
    final SessionCredentials credentials;
    final Path assetsDirectory;
    final Path gameDirectory;

    private ThinClientOptions(String host, int port, SessionCredentials credentials,
                              Path assetsDirectory, Path gameDirectory) {
        this.host = host;
        this.port = port;
        this.credentials = credentials;
        this.assetsDirectory = assetsDirectory;
        this.gameDirectory = gameDirectory;
    }

    static ThinClientOptions parse(String[] args) {
        String username = value(args, "--username", "Player");
        String rawUuid = value(args, "--uuid", null);
        String accessToken = value(args, "--accessToken", null);
        String quickPlay = value(args, "--quickPlayMultiplayer", null);
        String host = value(args, "--server", null);
        int port = integer(value(args, "--port", null), 25565);

        if (quickPlay != null && !quickPlay.isEmpty()) {
            HostAndPort address = address(quickPlay, port);
            host = address.host;
            port = address.port;
        } else if (host != null) {
            HostAndPort address = address(host, port);
            host = address.host;
            port = address.port;
        }
        String propertyAddress = System.getProperty("newhorizon.thin.server");
        if ((host == null || host.isEmpty()) && propertyAddress != null) {
            HostAndPort address = address(propertyAddress, port);
            host = address.host;
            port = address.port;
        }
        if (host == null || host.isEmpty()) {
            throw new IllegalArgumentException("Thin client requires --server or --quickPlayMultiplayer");
        }
        return new ThinClientOptions(host, port,
                new SessionCredentials(username, uuid(rawUuid), accessToken),
                Paths.get(value(args, "--assetsDir", "assets")),
                Paths.get(value(args, "--gameDir", ".")));
    }

    static boolean enabled(String[] args) {
        for (String argument : args) {
            if ("--thin-client".equals(argument)) return true;
        }
        return false;
    }

    private static String value(String[] args, String name, String fallback) {
        for (int index = 0; index + 1 < args.length; index++) {
            if (name.equals(args[index])) return args[index + 1];
        }
        return fallback;
    }

    private static int integer(String value, int fallback) {
        if (value == null) return fallback;
        int parsed = Integer.parseInt(value);
        if (parsed < 0 || parsed > 65535) throw new IllegalArgumentException("port");
        return parsed;
    }

    private static UUID uuid(String value) {
        if (value == null || value.isEmpty()) return null;
        String normalized = value;
        if (value.length() == 32) {
            normalized = value.substring(0, 8) + "-" + value.substring(8, 12)
                    + "-" + value.substring(12, 16) + "-" + value.substring(16, 20)
                    + "-" + value.substring(20);
        }
        return UUID.fromString(normalized);
    }

    private static HostAndPort address(String value, int fallbackPort) {
        if (value.startsWith("[")) {
            int end = value.indexOf(']');
            if (end < 0) throw new IllegalArgumentException("Invalid IPv6 server address");
            String host = value.substring(1, end);
            int port = end + 1 < value.length() && value.charAt(end + 1) == ':'
                    ? integer(value.substring(end + 2), fallbackPort) : fallbackPort;
            return new HostAndPort(host, port);
        }
        int colon = value.lastIndexOf(':');
        if (colon > 0 && value.indexOf(':') == colon) {
            return new HostAndPort(value.substring(0, colon),
                    integer(value.substring(colon + 1), fallbackPort));
        }
        return new HostAndPort(value, fallbackPort);
    }

    private static final class HostAndPort {
        final String host;
        final int port;

        HostAndPort(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }
}

