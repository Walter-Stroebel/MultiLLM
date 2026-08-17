/*
 * Copyright (c) 2025 by Walter Stroebel and InfComTec.
 * Copied verbatim (minus the SwingProps-dependent withProps() method,
 * to avoid pulling in jacksonwrap for this debug tool) from
 * /home/claude/catalog/advswing — see that catalog for the canonical copy.
 */
package nl.infcomtec.advswing;

import javax.swing.JTextArea;

public class ATextArea extends JTextArea {

    public ATextArea(String text) {
        super(text);
        setLineWrap(true);
        setWrapStyleWord(true);
    }

    public ATextArea() {
        setLineWrap(true);
        setWrapStyleWord(true);
    }

    public ATextArea(int rows, int columns) {
        super(rows, columns);
        setLineWrap(true);
        setWrapStyleWord(true);
    }

    public ATextArea(String text, int rows, int columns) {
        super(text, rows, columns);
        setLineWrap(true);
        setWrapStyleWord(true);
    }
}
