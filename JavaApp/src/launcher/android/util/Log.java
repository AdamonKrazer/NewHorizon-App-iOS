package android.util;

/** Minimal Android Log compatibility used by platform-neutral launcher code. */
public final class Log {
    private Log() {}

    public static int i(String tag, String message) {
        System.out.println("[" + tag + "] " + message);
        return 0;
    }

    public static int w(String tag, String message) {
        System.err.println("[" + tag + "][WARN] " + message);
        return 0;
    }

    public static int e(String tag, String message) {
        System.err.println("[" + tag + "][ERROR] " + message);
        return 0;
    }

    public static int e(String tag, String message, Throwable throwable) {
        e(tag, message);
        if (throwable != null) throwable.printStackTrace(System.err);
        return 0;
    }
}
