import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JTextField;
import javax.swing.UIManager;

import jacross.ColorRole;
import jacross.DesignLanguage;
import jacross.JaCrossLaf;
import jacross.Themes;
import jacross.Tokens;

/**
 * Headless verification of the JaCross L&F: token contrast across all
 * four language x scheme combinations, embedded Noto Sans TC coverage
 * (Latin + Traditional Chinese), and offscreen renders of the controls
 * localReceiver's window uses. Run: java -cp dist/localreceiver.jar tests/laf/LafTest.java
 */
public final class LafTest {
    static int pass = 0;
    static int fail = 0;

    public static void main(String[] args) throws Exception {
        Font font;
        try (var in = LafTest.class.getResourceAsStream("/jacross/NotoSansTC-Regular.ttf")) {
            font = Font.createFont(Font.TRUETYPE_FONT, in).deriveFont(13f);
        }
        check("embedded font is Noto Sans TC", font.getFamily().contains("Noto Sans TC"));
        check("font covers Latin", font.canDisplay('A'));
        check("font covers Traditional Chinese", font.canDisplay('繁') && font.canDisplay('檔'));
        // A CFF-flavoured font can report the right family yet rasterize
        // as a fallback face — only pixels prove the glyphs are ours.
        Font dialog = new Font(Font.DIALOG, Font.PLAIN, 40);
        check("Latin glyphs render from the embedded font (not a fallback)",
                textDiffers("AaGgRr", font.deriveFont(40f), dialog));
        check("CJK glyphs render from the embedded font (not a fallback)",
                textDiffers("檔案傳輸", font.deriveFont(40f), dialog));

        for (DesignLanguage language : DesignLanguage.values()) {
            for (boolean dark : new boolean[] {false, true}) {
                Tokens t = Themes.build(jacross.JaCross.BRAND_SEED, dark, language, font);
                String id = language + (dark ? "/dark" : "/light");
                check(id + " body contrast >= 4.5",
                        Tokens.contrast(t.color(ColorRole.ON_SURFACE),
                                t.color(ColorRole.SURFACE)) >= 4.5);
                check(id + " accent contrast >= 4.5",
                        Tokens.contrast(t.color(ColorRole.ON_ACCENT),
                                t.color(ColorRole.ACCENT)) >= 4.5);
                check(id + " container contrast >= 4.5",
                        Tokens.contrast(t.color(ColorRole.ON_ACCENT_CONTAINER),
                                t.color(ColorRole.ACCENT_CONTAINER)) >= 4.5);
            }
        }

        Tokens light = Themes.build(jacross.JaCross.BRAND_SEED, false, DesignLanguage.MATERIAL, font);
        UIManager.setLookAndFeel(new JaCrossLaf(light));
        check("defaultFont is the embedded family",
                UIManager.getFont("defaultFont").getFamily().contains("Noto Sans TC"));
        for (String key : new String[] {"Label.font", "Button.font", "CheckBox.font",
                "TextField.font", "ComboBox.font", "TitledBorder.font"}) {
            check(key + " is the embedded family",
                    UIManager.getFont(key).getFamily().contains("Noto Sans TC"));
        }

        BufferedImage buttonImg = render(new JButton("Start"), 120, 40);
        check("button renders", distinctColors(buttonImg) > 2);
        JCheckBox box = new JCheckBox("Allow directory browsing", true);
        check("checkbox renders", distinctColors(render(box, 240, 32)) > 2);
        check("textfield renders", distinctColors(render(new JTextField("4646"), 120, 34)) > 2);
        check("combobox renders",
                distinctColors(render(new JComboBox<>(new String[] {"localhost"}), 160, 34)) > 2);
        JLabel cjk = new JLabel("檔案傳輸伺服器");
        check("CJK label renders glyphs", distinctColors(render(cjk, 220, 30)) > 1);

        java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("laf-picker");
        java.nio.file.Files.createDirectory(tmp.resolve("subfolder"));
        localreceiver.gui.FolderPicker picker = new localreceiver.gui.FolderPicker(tmp, tmp);
        check("folder picker renders with entries", distinctColors(render(picker, 460, 280)) > 2);
        check("folder picker starts at the requested folder",
                picker.currentFolder().equals(tmp));

        Tokens darkTokens = Themes.build(jacross.JaCross.BRAND_SEED, true, DesignLanguage.MATERIAL, font);
        UIManager.setLookAndFeel(new JaCrossLaf(darkTokens));
        BufferedImage darkButton = render(new JButton("Start"), 120, 40);
        check("dark scheme differs from light", buttonImg.getRGB(60, 20) != darkButton.getRGB(60, 20));

        System.out.println(fail == 0 ? "TEST PASS" : "TEST FAIL");
        System.exit(fail == 0 ? 0 : 1);
    }

    static boolean textDiffers(String s, Font a, Font b) {
        return !java.util.Arrays.equals(renderText(s, a), renderText(s, b));
    }

    static int[] renderText(String s, Font f) {
        BufferedImage img = new BufferedImage(300, 60, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, 300, 60);
        g.setColor(java.awt.Color.BLACK);
        g.setFont(f);
        g.drawString(s, 5, 45);
        g.dispose();
        return img.getRGB(0, 0, 300, 60, null, 0, 300);
    }

    static BufferedImage render(JComponent c, int w, int h) {
        c.setSize(w, h);
        c.doLayout();
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        c.paint(g);
        g.dispose();
        return img;
    }

    static int distinctColors(BufferedImage img) {
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (int y = 0; y < img.getHeight(); y += 2) {
            for (int x = 0; x < img.getWidth(); x += 2) {
                seen.add(img.getRGB(x, y));
            }
        }
        return seen.size();
    }

    static void check(String label, boolean cond) {
        if (cond) {
            pass++;
            System.out.println("PASS: " + label);
        } else {
            fail++;
            System.out.println("FAIL: " + label);
        }
    }
}
