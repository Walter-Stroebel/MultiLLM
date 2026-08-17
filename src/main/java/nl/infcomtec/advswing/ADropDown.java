/*
 * Copyright (c) 2025 by Walter Stroebel and InfComTec.
 * Copied verbatim from /home/claude/catalog/advswing — see that
 * catalog for the canonical copy.
 */
package nl.infcomtec.advswing;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import javax.swing.JComboBox;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;

/**
 * Slightly extended JComboBox for the most common case one uses one.
 */
public abstract class ADropDown extends JComboBox<String> {

    protected final List<String> boxItems = new LinkedList<>();

    public ADropDown(String... items) {
        super(items);
        boxItems.addAll(Arrays.asList(items));
        getModel().addListDataListener(new ListDataListener() {
            @Override
            public void intervalAdded(ListDataEvent e) {
                for (int i = e.getIndex0(); i <= e.getIndex1(); i++) {
                    String item = getItemAt(i);
                    boxItems.add(item);
                    itemAdded(item);
                }
            }

            @Override
            public void intervalRemoved(ListDataEvent e) {
            }

            @Override
            public void contentsChanged(ListDataEvent e) {
            }
        });
        setEditable(true);

        Object ec = getEditor().getEditorComponent();
        if (ec instanceof javax.swing.JTextField) {
            javax.swing.JTextField tf = (javax.swing.JTextField) ec;
            tf.addActionListener(new java.awt.event.ActionListener() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    String text = tf.getText();
                    if (text != null && !text.isEmpty() && !boxItems.contains(text)) {
                        addItem(text);
                    }
                }
            });
        }
        addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                itemSelected((String) getSelectedItem());
            }
        });
    }

    public abstract void itemAdded(String item);

    public abstract void itemSelected(String item);
}
