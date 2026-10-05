package jacross;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Linux HiDPI: Java only honours integer GDK_SCALE automatically, so
 * on a GNOME desktop with fractional scaling or Large Text the UI
 * renders tiny. This probe reads the GNOME settings once and sets
 * {@code sun.java2d.uiScale} accordingly — it MUST run before any AWT
 * class loads, and it never overrides an explicit user choice
 * ({@code -Dsun.java2d.uiScale=...} or GDK_SCALE).
 */
public final class UiScale {
    private UiScale() {
    }

    public static void autoApply() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("linux")
                || System.getProperty("sun.java2d.uiScale") != null
                || System.getenv("GDK_SCALE") != null) {
            return;
        }
        double scale = 1.0;
        // Integer monitor scale (0 means "let GNOME decide" — ignore).
        Optional<String> monitor = gsettings("scaling-factor");
        if (monitor.isPresent()) {
            String digits = monitor.get().replaceAll("[^0-9]", "");
            if (!digits.isEmpty()) {
                int v = Integer.parseInt(digits);
                if (v > 1) {
                    scale = v;
                }
            }
        }
        // Accessibility text scaling (fractional, e.g. 1.25).
        Optional<String> text = gsettings("text-scaling-factor");
        if (text.isPresent()) {
            try {
                double v = Double.parseDouble(text.get().trim());
                if (v > 1.01) {
                    scale *= v;
                }
            } catch (NumberFormatException ignored) {
                // unparsable: keep the current scale
            }
        }
        if (scale > 1.01) {
            System.setProperty("sun.java2d.uiScale", String.valueOf(scale));
            System.out.println("localReceiver UI scale: " + scale
                    + " (from GNOME settings; override with -Dsun.java2d.uiScale=...)");
        }
    }

    private static Optional<String> gsettings(String key) {
        try {
            Process process = new ProcessBuilder(
                    "gsettings", "get", "org.gnome.desktop.interface", key)
                    .redirectErrorStream(true).start();
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return Optional.empty();
            }
            if (process.exitValue() != 0) {
                return Optional.empty();
            }
            return Optional.of(new String(process.getInputStream().readAllBytes()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
