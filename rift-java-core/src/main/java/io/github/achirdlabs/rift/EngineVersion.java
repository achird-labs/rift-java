package io.github.achirdlabs.rift;

/**
 * Compares rift engine version strings as {@code major.minor.patch}: a leading {@code v} and any
 * {@code -pre} suffix on the patch are ignored, and a missing component reads as zero. Shared by the
 * connect-time preflight and the per-feature engine gates.
 */
final class EngineVersion {

    private EngineVersion() {}

    /** Negative, zero or positive as {@code a} is older than, the same as, or newer than {@code b}. */
    static int compare(String a, String b) {
        int[] pa = parse(a);
        int[] pb = parse(b);
        for (int i = 0; i < 3; i++) {
            int cmp = Integer.compare(pa[i], pb[i]);
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    /** True when {@code version} is {@code minimum} or newer. */
    static boolean atLeast(String version, String minimum) {
        return compare(version, minimum) >= 0;
    }

    private static int[] parse(String version) {
        String v = version.isEmpty() ? version
                : (version.charAt(0) == 'v' || version.charAt(0) == 'V') ? version.substring(1) : version;
        String[] parts = v.split("\\.", 3);
        int[] out = new int[3];
        for (int i = 0; i < parts.length && i < 3; i++) {
            out[i] = leadingInt(parts[i]);
        }
        return out;
    }

    private static int leadingInt(String s) {
        int end = 0;
        while (end < s.length() && Character.isDigit(s.charAt(end))) {
            end++;
        }
        return end == 0 ? 0 : Integer.parseInt(s.substring(0, end));
    }
}
