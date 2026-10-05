package jacross;

import java.awt.Color;
import javax.swing.UIManager;

/**
 * Entry point: detect the platform (off the EDT — subprocess probes),
 * build the token set, and install the look and feel (on the EDT,
 * before any component is constructed).
 *
 * <p>Policy, per the JaCross spec: FLUENT on Windows, MATERIAL
 * elsewhere; scheme follows the OS (light when undetectable); seed is
 * the OS accent colour, falling back to the brand blue.
 */
public final class JaCross {
    /** localReceiver brand blue — also the PWA's theme colour. */
    public static final int BRAND_SEED = 0x2563EB;

    private JaCross() {
    }

    /** Platform probes + font load. Call once, off the EDT. */
    public static Tokens detect() {
        Platform platform = Platform.detect();
        DesignLanguage language = platform.os() == Platform.OS.WINDOWS
                ? DesignLanguage.FLUENT : DesignLanguage.MATERIAL;
        boolean dark = platform.osDark().orElse(false);
        int seed = platform.accentColor().map(Color::getRGB).orElse(BRAND_SEED);
        java.awt.Font font = Fonts.ui(13f);
        System.out.println("localReceiver UI font: " + font.getFamily());
        return Themes.build(seed, dark, language, font);
    }

    /** Installs the L&F for the given tokens. Call on the EDT. */
    public static void apply(Tokens tokens) {
        try {
            UIManager.setLookAndFeel(new JaCrossLaf(tokens));
        } catch (javax.swing.UnsupportedLookAndFeelException e) {
            throw new IllegalStateException(e);
        }
    }
}
