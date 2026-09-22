package com.newhorizon.thinclient.display;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Exercises the actual loopback resource route used by Reynard. */
public final class MinePadResourcesSelfTest {
    public static void run() throws Exception {
        String local = MinePadResources.resolve("mod://webdisplays/main.html");
        check(local.startsWith("http://127.0.0.1:"), "loopback binding");
        check(MinePadResources.canonical(local).equals("mod://webdisplays/main.html"), "canonical URL");
        check(MinePadResources.resolve("https://example.invalid/").equals("https://example.invalid/"), "external navigation");
        HttpURLConnection page = (HttpURLConnection)new URL(local + "?v=1").openConnection();
        page.setReadTimeout(3000);
        try {
            check(page.getResponseCode() == 200, "home page status");
            check(page.getContentType().startsWith("text/html"), "home page MIME");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (InputStream input = page.getInputStream()) {
                byte[] buffer = new byte[4096]; int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            }
            check(new String(output.toByteArray(), StandardCharsets.UTF_8).contains("<html>"), "original bundled HTML");
        } finally { page.disconnect(); }
        HttpURLConnection denied = (HttpURLConnection)new URL(MinePadResources.resolve("mod://webdisplays/../secret")).openConnection();
        denied.setReadTimeout(3000);
        try { check(denied.getResponseCode() == 404, "resource allowlist"); }
        finally { denied.disconnect(); }
        System.out.println("MinePad iOS resource tests passed: loopback HTTP, bundled HTML, MIME, canonical URLs, path allowlist");
    }
    private static void check(boolean value, String name) { if (!value) throw new AssertionError(name); }
}
