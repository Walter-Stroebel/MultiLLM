/*
 * Copyright (c) 2025 by Walter Stroebel and InfComTec.
 * Copied verbatim (minus the SwingProps-dependent withProps() method,
 * to avoid pulling in jacksonwrap for this debug tool) from
 * /home/claude/catalog/advswing — see that catalog for the canonical copy.
 */
package nl.infcomtec.advswing;

import javax.swing.JCheckBox;

public class ACheckBox extends JCheckBox {

    public ACheckBox() {
    }

    public ACheckBox(EzAction a) {
        super(a);
        if (null != a.font) {
            setFont(a.font);
        }
        if (null != a.background) {
            setBackground(a.background);
        }
        if (null != a.foreground) {
            setForeground(a.foreground);
        }
    }
}
