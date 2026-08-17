/*
 * Copyright (c) 2025 by Walter Stroebel and InfComTec.
 * Copied verbatim (minus the JComponent-column add() helper, which pulls
 * in ALabel/ATextField assumptions this tool doesn't need) from
 * /home/claude/catalog/advswing — see that catalog for the canonical copy.
 */
package nl.infcomtec.advswing;

import java.awt.GridBagConstraints;
import java.awt.Insets;

/**
 * Stateful GridBagConstraints cursor. next() returns the current position and
 * advances the cursor.
 */
public class GBC extends GridBagConstraints {

    public int cols = 0, rows = 0;

    public GBC() {
        this.anchor = CENTER;
        this.fill = NONE;
        this.gridheight = 1;
        this.gridwidth = 1;
        this.gridx = 0;
        this.gridy = 0;
        this.insets = new Insets(1, 1, 1, 1);
        this.ipadx = 0;
        this.ipady = 0;
        this.weightx = 0;
        this.weighty = 0;
    }

    private GBC(GBC last) {
        super(last.gridx,
                last.gridy,
                last.gridwidth,
                last.gridheight,
                last.weightx,
                last.weighty,
                last.anchor,
                last.fill,
                last.insets,
                last.ipadx,
                last.ipady);
    }

    public GBC here() {
        return new GBC(this);
    }

    /**
     * Advances the cursor and returns the previous position. Equivalent to
     * post-increment.
     */
    public GBC next() {
        GBC ret = here();
        if (cols > 0) {
            gridx++;
            if (gridx >= cols) {
                gridx = 0;
                gridy++;
            }
        } else if (rows > 0) {
            gridy++;
            if (gridy >= rows) {
                gridx++;
                gridy = 0;
            }
        }
        return ret;
    }

    public GBC withCols(int cols) {
        this.cols = cols;
        this.rows = 0;
        return this;
    }

    public GBC withRows(int rows) {
        this.cols = 0;
        this.rows = rows;
        return this;
    }
}
