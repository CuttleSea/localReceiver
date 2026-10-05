package jacross;

import java.awt.Color;
import java.util.ArrayList;
import javax.swing.UIDefaults;
import javax.swing.plaf.BorderUIResource;
import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.FontUIResource;
import javax.swing.plaf.InsetsUIResource;
import javax.swing.plaf.basic.BasicLookAndFeel;

import jacross.plaf.JaCrossBorders;
import jacross.plaf.JaCrossIcons;

/**
 * JaCross — a token-driven Fluent 2 / Material 3 look and feel, Tier 0
 * (pure java.desktop). Scoped to the components localReceiver's control
 * window uses; everything else inherits Basic delegates recoloured by
 * the global defaults sweep. Every value placed in UIDefaults is a
 * *UIResource so runtime theme switches would replace it.
 */
public class JaCrossLaf extends BasicLookAndFeel {
    private final Tokens tokens;

    public JaCrossLaf(Tokens tokens) {
        this.tokens = tokens;
    }

    public Tokens tokens() {
        return tokens;
    }

    @Override
    public String getID() {
        return "JaCross";
    }

    @Override
    public String getName() {
        return "JaCross";
    }

    @Override
    public String getDescription() {
        return "JaCross — token-driven Fluent 2 / Material 3 Expressive L&F (Tier 0)";
    }

    @Override
    public boolean isNativeLookAndFeel() {
        return false;
    }

    @Override
    public boolean isSupportedLookAndFeel() {
        return true;
    }

    @Override
    protected void initClassDefaults(UIDefaults d) {
        super.initClassDefaults(d);
        // Class literals, not name strings: the build compiles only
        // classes reachable from Main, so string-only references would
        // silently leave the delegates out of the jar.
        d.putDefaults(new Object[] {
            "ButtonUI", jacross.plaf.JaCrossButtonUI.class.getName(),
            "ComboBoxUI", jacross.plaf.JaCrossComboBoxUI.class.getName(),
        });
    }

    @Override
    protected void initComponentDefaults(UIDefaults d) {
        super.initComponentDefaults(d);
        FontUIResource base = new FontUIResource(tokens.font());
        ColorUIResource surface = res(tokens.color(ColorRole.SURFACE));
        ColorUIResource onSurface = res(tokens.color(ColorRole.ON_SURFACE));

        d.put("defaultFont", base);
        for (Object key : new ArrayList<>(d.keySet())) {
            String k = String.valueOf(key);
            if (k.endsWith(".font")) {
                d.put(key, base);
            } else if (k.endsWith(".background")) {
                d.put(key, surface);
            } else if (k.endsWith(".foreground")) {
                d.put(key, onSurface);
            }
        }

        ColorUIResource fieldBg = res(tokens.isDark()
                ? tokens.color(ColorRole.SURFACE_CONTAINER_LOW) : Color.WHITE);
        ColorUIResource selection = res(tokens.color(ColorRole.ACCENT_CONTAINER));
        ColorUIResource onSelection = res(tokens.color(ColorRole.ON_ACCENT_CONTAINER));
        BorderUIResource fieldBorder =
                new BorderUIResource(new JaCrossBorders.Field(tokens));

        d.put("Button.margin", new InsetsUIResource(5, 14, 5, 14));
        d.put("CheckBox.icon", new JaCrossIcons.Check(tokens));
        d.put("TextField.background", fieldBg);
        d.put("TextField.border", fieldBorder);
        d.put("TextField.caretForeground", onSurface);
        d.put("TextField.selectionBackground", selection);
        d.put("TextField.selectionForeground", onSelection);
        d.put("TextField.inactiveBackground", fieldBg);
        d.put("ComboBox.background", fieldBg);
        d.put("ComboBox.selectionBackground", selection);
        d.put("ComboBox.selectionForeground", onSelection);
        d.put("List.selectionBackground", selection);
        d.put("List.selectionForeground", onSelection);
        d.put("ToolTip.background", res(tokens.color(ColorRole.SURFACE_CONTAINER_HIGH)));
        d.put("ToolTip.border", new BorderUIResource(
                new JaCrossBorders.Line(tokens, ColorRole.OUTLINE_VARIANT)));
        d.put("OptionPane.background", surface);
        d.put("Panel.background", surface);
        d.put("Separator.foreground", res(tokens.color(ColorRole.OUTLINE_VARIANT)));

        // Painters read the live token set through UIManager.
        d.put("jacross.tokens", tokens);
    }

    private static ColorUIResource res(Color c) {
        return new ColorUIResource(c);
    }
}
