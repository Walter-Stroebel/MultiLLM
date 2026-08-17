/*
 * Copyright (c) 2025 by Walter Stroebel and InfComTec.
 * Copied verbatim from /home/claude/catalog/advswing — see that
 * catalog for the canonical copy.
 */
package nl.infcomtec.advswing;

import java.awt.Color;
import java.awt.Font;
import java.awt.event.ActionEvent;
import javax.swing.AbstractAction;

public abstract class EzAction extends AbstractAction {

    public Color background;
    public Color foreground;
    public Font font;

    public EzAction(String name) {
        super(name);
    }

    @Override
    public abstract void actionPerformed(ActionEvent e);
}
