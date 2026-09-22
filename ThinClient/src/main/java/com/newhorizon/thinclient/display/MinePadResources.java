package com.newhorizon.thinclient.display;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Serves the original MinePad pages from the component JAR over a loopback socket. */
final class MinePadResources {
    private static final String[] FILES = {"main.html", "newhorizon.png", "blacklisted.html", "io.html", "jquery.js", "wdlib.js"};
    private static MinePadResources instance;
    private final ServerSocket server;
    private final String origin;

    private MinePadResources() throws IOException {
        server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
        origin = "http://127.0.0.1:" + server.getLocalPort() + "/mod/webdisplays/";
        Thread worker = new Thread(() -> {
            while (!server.isClosed()) {
                try { serve(server.accept()); }
                catch (IOException ignored) { /* A cancelled page must not stop other tabs. */ }
            }
        }, "NH-MinePad-Resources");
        worker.setDaemon(true);
        worker.start();
    }

    static synchronized String resolve(String url) {
        String resource = url.startsWith("mod://webdisplays/") ? url.substring(18)
                : url.startsWith("webdisplays://") ? url.substring(14) : null;
        if (resource == null) return url;
        if (instance == null) {
            try { instance = new MinePadResources(); }
            catch (IOException error) { throw new IllegalStateException("Cannot start MinePad resources", error); }
        }
        return instance.origin + resource;
    }

    static synchronized String canonical(String url) {
        return instance != null && url.startsWith(instance.origin)
                ? "mod://webdisplays/" + url.substring(instance.origin.length()) : url;
    }

    private void serve(Socket connection) throws IOException {
        try (Socket socket = connection) {
            socket.setSoTimeout(2000);
            InputStream input = socket.getInputStream();
            ByteArrayOutputStream header = new ByteArrayOutputStream();
            int state = 0;
            while (header.size() < 8192) {
                int value = input.read();
                if (value < 0) return;
                header.write(value);
                state = value == "\r\n\r\n".charAt(state) ? state + 1 : value == '\r' ? 1 : 0;
                if (state == 4) break;
            }
            String[] request = new String(header.toByteArray(), StandardCharsets.US_ASCII).split(" ", 3);
            String file = request.length > 1 && request[0].equals("GET") && request[1].startsWith("/mod/webdisplays/")
                    ? request[1].substring(17).split("[?#]", 2)[0] : "";
            boolean found = Arrays.asList(FILES).contains(file);
            byte[] body;
            if (found) {
                try (InputStream asset = MinePadResources.class.getResourceAsStream("/minepad/" + file);
                     ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    if (asset == null) throw new FileNotFoundException(file);
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = asset.read(buffer)) != -1) {
                        if (out.size() + count > 2 * 1024 * 1024) throw new IOException("Resource limit");
                        out.write(buffer, 0, count);
                    }
                    body = out.toByteArray();
                }
            } else { body = "Not found".getBytes(StandardCharsets.US_ASCII); }
            String mime = file.endsWith(".png") ? "image/png" : file.endsWith(".js")
                    ? "application/javascript; charset=utf-8" : "text/html; charset=utf-8";
            OutputStream output = socket.getOutputStream();
            output.write(("HTTP/1.1 " + (found ? "200 OK" : "404 Not Found")
                    + "\r\nContent-Type: " + mime + "\r\nContent-Length: " + body.length
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            output.write(body);
        }
    }
}
