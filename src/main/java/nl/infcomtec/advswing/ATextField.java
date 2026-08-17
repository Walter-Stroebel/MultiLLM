/*
 * Copyright (c) 2025 by Walter Stroebel and InfComTec.
 * Copied verbatim (minus the SwingProps-dependent withProps() method,
 * to avoid pulling in jacksonwrap for this debug tool) from
 * /home/claude/catalog/advswing — see that catalog for the canonical copy.
 */
package nl.infcomtec.advswing;

import java.awt.Dimension;
import javax.swing.JTextField;

public class ATextField extends JTextField {

    private final SizePolicy widthPolicy;
    private final SizePolicy heightPolicy;
    private final Dimension fixedSize;

    public ATextField() {
        this.widthPolicy = SizePolicy.FLEXIBLE;
        this.heightPolicy = SizePolicy.FLEXIBLE;
        this.fixedSize = null;
    }

    public ATextField(String text) {
        super(text);
        this.widthPolicy = SizePolicy.FIXED;
        this.heightPolicy = SizePolicy.FIXED;
        this.fixedSize = null;
    }

    public ATextField(int columns) {
        super(columns);
        this.widthPolicy = SizePolicy.FIXED;
        this.heightPolicy = SizePolicy.FIXED;
        this.fixedSize = null;
    }

    public ATextField(String text, int columns) {
        super(text, columns);
        this.widthPolicy = SizePolicy.FIXED;
        this.heightPolicy = SizePolicy.FIXED;
        this.fixedSize = null;
    }

    public ATextField(
            SizePolicy widthPolicy,
            SizePolicy heightPolicy,
            Dimension fixedSize
    ) {
        this.widthPolicy = widthPolicy;
        this.heightPolicy = heightPolicy;
        this.fixedSize = fixedSize;
    }

    @Override
    public Dimension getMinimumSize() {
        return intSize();
    }

    @Override
    public Dimension getMaximumSize() {
        Dimension d = intSize();
        int w = (widthPolicy == SizePolicy.FLEXIBLE) ? Integer.MAX_VALUE : d.width;
        int h = (heightPolicy == SizePolicy.FLEXIBLE) ? Integer.MAX_VALUE : d.height;
        return new Dimension(w, h);
    }

    private Dimension intSize() {
        if (fixedSize != null) {
            return fixedSize;
        }
        return getPreferredSize();
    }

    public enum SizePolicy {
        FIXED,
        FLEXIBLE
    }
}
