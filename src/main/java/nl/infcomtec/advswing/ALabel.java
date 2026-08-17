/*
 * Copyright (c) 2025 by Walter Stroebel and InfComTec.
 * Copied verbatim (minus the SwingProps-dependent withProps() method,
 * to avoid pulling in jacksonwrap for this debug tool) from
 * /home/claude/catalog/advswing — see that catalog for the canonical copy.
 */
package nl.infcomtec.advswing;

import javax.swing.JLabel;

public class ALabel extends JLabel {

    public ALabel(String text) {
        super(text);
    }

    public ALabel() {
    }
}
