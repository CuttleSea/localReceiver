package jacross;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Loads the embedded Noto Sans TC (SIL OFL, subset OTF) — one family
 * with full Traditional Chinese and competent Latin coverage, so the
 * GUI renders identically on every OS with no fallback boxes.
 *
 * <p>The font ships as a TrueType-outline (glyf) static instance —
 * CFF-outline OTFs load with the right family name but rasterize as a
 * fallback face on some JDK builds (observed on Windows and Ubuntu),
 * which is exactly the failure a family-name check cannot catch; the
 * L&F test therefore compares rendered pixels against the Dialog font.
 *
 * <p>Primary path: the font is extracted once to the config directory
 * and loaded with {@code Font.createFont(File)} — the InputStream
 * variant spools through {@code java.io.tmpdir}, which is exactly the
 * kind of machine-specific dependency that fails silently. Every
 * fallback (stream, then the logical Dialog font) prints a warning so
 * a wrong-looking UI is diagnosable, never silent. Java 2D derives
 * bold synthetically from the single Regular weight.
 */
final class Fonts {
    private static final String RESOURCE = "/jacross/NotoSansTC-Regular.ttf";
    private static final String FILE_NAME = "NotoSansTC-Regular.ttf";

    private Fonts() {
    }

    /** The UI font at the given size; logical Dialog if all paths fail. */
    static Font ui(float size) {
        Font font = fromExtractedFile();
        if (font == null) {
            font = fromStream();
        }
        if (font != null && font.canDisplay('檔')) {
            GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(font);
            return font.deriveFont(size);
        }
        if (font != null) {
            System.err.println("localReceiver: embedded font loaded but lacks CJK glyphs;"
                    + " falling back to the system font");
        }
        return new Font(Font.DIALOG, Font.PLAIN, Math.round(size));
    }

    private static Font fromExtractedFile() {
        try {
            Path dir = localreceiver.Config.dir();
            Files.createDirectories(dir);
            // v0.19 extracted a CFF-flavoured OTF here; remove it.
            Files.deleteIfExists(dir.resolve("NotoSansTC-Regular.otf"));
            Path file = dir.resolve(FILE_NAME);
            long resourceSize;
            try (InputStream in = Fonts.class.getResourceAsStream(RESOURCE)) {
                if (in == null) {
                    System.err.println("localReceiver: embedded font resource missing from the jar");
                    return null;
                }
                if (!Files.exists(file) || Files.size(file) == 0) {
                    Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
                }
                resourceSize = Files.size(file);
            }
            if (resourceSize == 0) {
                return null;
            }
            return Font.createFont(Font.TRUETYPE_FONT, file.toFile());
        } catch (Exception e) {
            System.err.println("localReceiver: could not load the UI font from the config dir: " + e);
            return null;
        }
    }

    private static Font fromStream() {
        try (InputStream in = Fonts.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return null;
            }
            return Font.createFont(Font.TRUETYPE_FONT, in);
        } catch (Exception e) {
            System.err.println("localReceiver: could not load the UI font from the jar: " + e);
            return null;
        }
    }
}
