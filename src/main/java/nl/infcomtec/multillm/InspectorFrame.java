/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.multillm;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import javax.swing.AbstractListModel;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JScrollPane;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

/**
 * The live call inspector's main window: a {@link JList} of the LLM calls
 * that have passed through the gateway, newest at the top. Double-click a
 * row to open a {@link CallDetailFrame} for that one call.
 * <p>
 * Fed by {@link CallLog} — this frame registers as a {@link CallLog.Listener}
 * and every {@code callRecorded} notification is marshalled onto the Swing
 * EDT before it touches the list model. The list only ever shows what
 * {@link CallLog#snapshot()} returns, so it is naturally bounded by the
 * configured ring size.
 * <p>
 * Only ever constructed from {@code MultiLLM.main} when the config's
 * {@code "inspector"} block authorises it. Closing the window disposes it
 * without stopping the gateway.
 */
final class InspectorFrame extends JFrame implements CallLog.Listener {

    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final RingListModel model = new RingListModel();
    private final JList<CallLog.Entry> list = new JList<>(model);
    private final boolean revealSecrets;

    static void launch(boolean revealSecrets) {
        SwingUtilities.invokeLater(new Launch(revealSecrets));
    }

    private static final class Launch implements Runnable {

        private final boolean revealSecrets;

        Launch(boolean revealSecrets) {
            this.revealSecrets = revealSecrets;
        }

        @Override
        public void run() {
            new InspectorFrame(revealSecrets).setVisible(true);
        }
    }

    private InspectorFrame(boolean revealSecrets) {
        super("MultiLLM — LLM call inspector");
        this.revealSecrets = revealSecrets;
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout());

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        list.setCellRenderer(new RowRenderer());
        list.addMouseListener(new OpenOnDoubleClick());

        add(new JLabel("  Double-click a call for full request / response detail"
                + (revealSecrets ? "   —   credentials shown in clear (revealSecrets)" : "")),
                BorderLayout.NORTH);
        add(new JScrollPane(list), BorderLayout.CENTER);

        model.reload();
        CallLog.addListener(this);

        setPreferredSize(new Dimension(760, 480));
        pack();
        setLocationRelativeTo(null);
    }

    @Override
    public void callRecorded(CallLog.Entry entry) {
        SwingUtilities.invokeLater(new Reload());
    }

    private final class Reload implements Runnable {

        @Override
        public void run() {
            model.reload();
        }
    }

    private final class OpenOnDoubleClick extends MouseAdapter {

        @Override
        public void mouseClicked(MouseEvent e) {
            if (2 != e.getClickCount()) {
                return;
            }
            CallLog.Entry entry = list.getSelectedValue();
            if (null != entry) {
                new CallDetailFrame(entry, revealSecrets).setVisible(true);
            }
        }
    }

    private static String describe(CallLog.Entry e) {
        String status = null == e.transcript ? "no-call"
                : (e.transcript.status > 0 ? "HTTP " + e.transcript.status : "no response");
        long ms = null == e.transcript ? 0L : e.transcript.totalMillis();
        return String.format("%s  %-14s %-22s %-12s %5d ms",
                CLOCK.format(Instant.ofEpochMilli(e.at)),
                e.endpointName, e.model, status, ms);
    }

    private static final class RowRenderer implements ListCellRenderer<CallLog.Entry> {

        private final JLabel label = new JLabel();

        RowRenderer() {
            label.setOpaque(true);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends CallLog.Entry> jl,
                CallLog.Entry value, int index, boolean selected, boolean focused) {
            label.setText(describe(value));
            label.setFont(jl.getFont());
            if (selected) {
                label.setBackground(jl.getSelectionBackground());
                label.setForeground(jl.getSelectionForeground());
            } else {
                label.setBackground(jl.getBackground());
                label.setForeground(jl.getForeground());
            }
            return label;
        }
    }

    /** Backed entirely by {@link CallLog#snapshot()}; refreshed wholesale on the EDT. */
    private static final class RingListModel extends AbstractListModel<CallLog.Entry> {

        private List<CallLog.Entry> rows = List.of();

        void reload() {
            int oldSize = rows.size();
            rows = CallLog.snapshot();
            if (oldSize > 0) {
                fireIntervalRemoved(this, 0, oldSize - 1);
            }
            if (!rows.isEmpty()) {
                fireIntervalAdded(this, 0, rows.size() - 1);
            }
        }

        @Override
        public int getSize() {
            return rows.size();
        }

        @Override
        public CallLog.Entry getElementAt(int index) {
            return rows.get(index);
        }
    }
}
