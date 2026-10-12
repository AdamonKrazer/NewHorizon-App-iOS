package net.kdt.pojavlaunch.gecko;

import java.io.IOException;
import java.io.InputStream;

/** Uses the unchanged Android policy for every Reynard MinePad navigation. */
public final class IOSMinePadPolicy {
    private IOSMinePadPolicy() {}
    static {
        try (InputStream input = IOSMinePadPolicy.class.getResourceAsStream("/minepad/adult-domains.txt")) {
            if (input == null) throw new IOException("Missing MinePad domain list");
            MinePadNavigationPolicy.load(input);
        } catch (IOException error) { throw new ExceptionInInitializerError(error); }
    }
    public static String filter(String url) { return MinePadNavigationPolicy.filter(url); }
    public static void selfTest() {
        if (MinePadNavigationPolicy.domainCount() < 1000) throw new AssertionError("Domain snapshot missing");
        for (String url : new String[]{"https://pornhub.com/", "https://child.xhamster.com/",
                "https://example.org/?q=pornografia", "https://example.org/?url=https%253A%252F%252Fxnxx.com"}) {
            if (!MinePadNavigationPolicy.BLOCKED_PAGE.equals(filter(url))) throw new AssertionError("Policy did not block " + url);
        }
        for (String url : new String[]{"https://minecraft.net/", "https://example.org/?q=minecraft", "mod://webdisplays/main.html"}) {
            if (!url.equals(filter(url))) throw new AssertionError("Policy blocked allowed URL");
        }
        System.out.println("MinePad iOS navigation policy tests passed: domains, search, nested redirects and allowed URLs");
    }
}
