/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.multillm;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.time.Instant;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.WindowConstants;
import nl.infcomtec.advswing.AButton;
import nl.infcomtec.advswing.EzAction;
import nl.infcomtec.nethttp.Rest;
import nl.infcomtec.nethttp.Secrets;
import nl.infcomtec.nethttp.Transcript;

/**
 * The full detail of one captured LLM call, as tabs: an overview, the
 * request as sent, the response as received, and the raw
 * {@link Transcript#render()} block (the {@code tcpdump -A} view).
 * <p>
 * Credential values follow the same masking as {@code transcriptText()}:
 * shown in clear only when the config's {@code inspector.revealSecrets} was
 * set, which also flips {@code Rest.revealSecretsInTranscript} at startup so
 * the captured transcript renders unmasked here. Otherwise every tab shows
 * {@code <redacted>} in sensitive headers, exactly as a log line would.
 * <p>
 * A streamed call has no captured response body — the gateway relays those
 * bytes straight through — so the Response tab says so and shows only the
 * request half.
 */
final class CallDetailFrame extends JFrame {

    private final CallLog.Entry entry;
    private final boolean revealSecrets;

    CallDetailFrame(CallLog.Entry entry, boolean revealSecrets) {
        super("Call — " + entry.endpointName + " / " + entry.model
                + " @ " + Instant.ofEpochMilli(entry.at));
        this.entry = entry;
        this.revealSecrets = revealSecrets;
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Overview", wrap(overviewText()));
        tabs.addTab("Request", wrap(requestText()));
        tabs.addTab("Response", wrap(responseText()));
        tabs.addTab("Raw transcript", rawTranscriptPanel());

        setLayout(new BorderLayout());
        add(tabs, BorderLayout.CENTER);

        setPreferredSize(new Dimension(820, 620));
        pack();
        setLocationRelativeTo(null);
    }

    private static JScrollPane wrap(String text) {
        JTextArea area = new JTextArea(text);
        area.setEditable(false);
        area.setLineWrap(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        return new JScrollPane(area);
    }

    private JPanel rawTranscriptPanel() {
        String raw = null == entry.transcript ? "(no transcript — call failed before one was built)"
                : entry.transcript.render();

        JTextArea area = new JTextArea(raw);
        area.setEditable(false);
        area.setLineWrap(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(new JScrollPane(area), BorderLayout.CENTER);
        panel.add(new AButton(new CopyRaw(raw)), BorderLayout.SOUTH);
        return panel;
    }

    private final class CopyRaw extends EzAction {

        private final String raw;

        CopyRaw(String raw) {
            super("Copy to clipboard");
            this.raw = raw;
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(raw), null);
        }
    }

    private String overviewText() {
        StringBuilder sb = new StringBuilder();
        sb.append("endpoint : ").append(entry.endpointName).append('\n');
        sb.append("model    : ").append(entry.model).append('\n');
        sb.append("when     : ").append(Instant.ofEpochMilli(entry.at)).append('\n');
        Transcript t = entry.transcript;
        if (null == t) {
            sb.append("\n(no transcript — the call failed before Rest built one)\n");
            return sb.toString();
        }
        sb.append("uri      : ").append(maskedUri(t)).append('\n');
        sb.append("assembly : ").append(null == t.uriSource ? "(n/a)" : t.uriSource).append('\n');
        sb.append("status   : ").append(t.status > 0 ? "HTTP " + t.status : "no response").append('\n');
        sb.append("elapsed  : ").append(t.totalMillis()).append(" ms\n");
        if (!t.errors.isEmpty()) {
            sb.append("\nerrors :\n");
            for (String err : t.errors) {
                sb.append("         ").append(err).append('\n');
            }
        }
        return sb.toString();
    }

    private String requestText() {
        Transcript t = entry.transcript;
        if (null == t) {
            return "(no transcript)";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(null == t.verb ? "?" : t.verb).append(' ').append(maskedUri(t)).append("\n\n");
        for (Rest.Header h : t.requestHeaders) {
            sb.append(h.name).append(": ").append(maskedHeaderValue(h)).append('\n');
        }
        sb.append('\n');
        sb.append(null == t.requestBody ? "(no request body)" : Transcript.prettyIfJson(t.requestBody));
        return sb.toString();
    }

    private String responseText() {
        Transcript t = entry.transcript;
        if (null == t) {
            return "(no transcript)";
        }
        if (t.status <= 0) {
            return "(no response — call did not complete)";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP ").append(t.status).append("\n\n");
        for (Rest.Header h : t.responseHeaders) {
            sb.append(h.name).append(": ").append(maskedHeaderValue(h)).append('\n');
        }
        sb.append('\n');
        if (null == t.responseBody) {
            sb.append("(response body not captured — a streamed call relays bytes straight "
                    + "through to the client, so there is nothing to retain here)");
        } else {
            sb.append(Transcript.prettyIfJson(t.responseBody));
        }
        return sb.toString();
    }

    // ---- masking: mirrors Transcript.render()'s behaviour for the field-by-field views ----

    private String maskedUri(Transcript t) {
        if (null == t.uri) {
            return "(no URI)";
        }
        return revealSecrets ? t.uri : Secrets.maskUrlQuery(t.uri);
    }

    private String maskedHeaderValue(Rest.Header h) {
        if (!revealSecrets && Secrets.isSensitiveHeader(h.name)) {
            return Secrets.MASK;
        }
        return h.value;
    }
}
