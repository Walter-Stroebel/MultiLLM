/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.multillm;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
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
 * <p>
 * <b>Image bodies.</b> A base64 image inlined in a request body
 * ({@code data:image/png;base64,...}) is elided from every text view here
 * (see {@link DataUri#elideLargeBase64}) — showing a ~MB blob line-by-line
 * in a text area is useless and would defeat the {@code maxCalls} ring.
 * The Request tab instead offers a "copy the sent image to the clipboard"
 * button (from the inlined base64, or by fetching an image URL — no bytes
 * on disk either way), so the user can paste it into an image tool and
 * inspect it, e.g. at a reduced scale to check for a downscale-OCR
 * prompt injection.
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
        tabs.addTab("Request", requestPanel());
        tabs.addTab("Response", wrap(responseText()));
        tabs.addTab("Raw transcript", rawTranscriptPanel());

        setLayout(new BorderLayout());
        add(tabs, BorderLayout.CENTER);

        setPreferredSize(new Dimension(820, 620));
        pack();
        setLocationRelativeTo(null);
    }

    private static JScrollPane wrap(String text) {
        return new JScrollPane(readOnlyArea(text));
    }

    private static JTextArea readOnlyArea(String text) {
        JTextArea area = new JTextArea(text);
        area.setEditable(false);
        area.setLineWrap(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        return area;
    }

    private JPanel rawTranscriptPanel() {
        String raw = null == entry.transcript ? "(no transcript — call failed before one was built)"
                : DataUri.elideLargeBase64(entry.transcript.render());

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(new JScrollPane(readOnlyArea(raw)), BorderLayout.CENTER);
        panel.add(new AButton(new CopyText("Copy to clipboard", raw)), BorderLayout.SOUTH);
        return panel;
    }

    private JPanel requestPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(new JScrollPane(readOnlyArea(requestText())), BorderLayout.CENTER);

        Transcript t = entry.transcript;
        String body = null == t ? null : t.requestBody;
        if (null != body && (DataUri.hasImage(body) || null != firstImageUrl(body))) {
            panel.add(new AButton(new CopyImage(body)), BorderLayout.SOUTH);
        }
        return panel;
    }

    // ---- clipboard actions ----

    private final class CopyText extends EzAction {

        private final String text;

        CopyText(String label, String text) {
            super(label);
            this.text = text;
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(text), null);
        }
    }

    /**
     * Copies the image the request carried onto the clipboard. Source is
     * either an inlined {@code data:image/*;base64,...} payload or, failing
     * that, an image URL in the body which is fetched into memory. Nothing
     * is written to disk.
     */
    private final class CopyImage extends EzAction {

        private final String requestBody;

        CopyImage(String requestBody) {
            super(DataUri.hasImage(requestBody)
                    ? "Copy sent image to clipboard (inlined base64)"
                    : "Copy sent image to clipboard (fetch " + firstImageUrl(requestBody) + ")");
            this.requestBody = requestBody;
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            try {
                byte[] bytes;
                DataUri.Image img = DataUri.firstImage(requestBody);
                if (null != img) {
                    bytes = img.bytes;
                } else {
                    String url = firstImageUrl(requestBody);
                    if (null == url) {
                        fail("No inlined image decoded and no image URL found in the body.");
                        return;
                    }
                    bytes = fetch(url);
                    if (null == bytes) {
                        fail("Could not fetch the image URL — see the gateway log.");
                        return;
                    }
                }
                Image image = ImageIO.read(new ByteArrayInputStream(bytes));
                if (null == image) {
                    fail("The bytes are not an image format ImageIO can read.");
                    return;
                }
                Toolkit.getDefaultToolkit().getSystemClipboard()
                        .setContents(new ImageTransferable(image), null);
                JOptionPane.showMessageDialog(CallDetailFrame.this,
                        image.getWidth(null) + " × " + image.getHeight(null)
                        + " image copied to the clipboard.",
                        "Copied", JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception ex) {
                fail(ex.toString());
            }
        }

        private void fail(String message) {
            JOptionPane.showMessageDialog(CallDetailFrame.this, message,
                    "Could not copy image", JOptionPane.WARNING_MESSAGE);
        }
    }

    /**
     * GET the URL's bytes into memory — no file on disk. Null on any
     * failure (logged, not thrown). A plain {@link java.net.HttpURLConnection}
     * rather than {@code Rest}: {@code Rest} decodes the body as text, which
     * mangles binary image bytes; here we need them verbatim.
     */
    private static byte[] fetch(String url) {
        try {
            java.net.HttpURLConnection conn =
                    (java.net.HttpURLConnection) java.net.URI.create(url).toURL().openConnection();
            conn.setRequestMethod("GET");
            int status = conn.getResponseCode();
            if (200 != status) {
                System.out.println(System.currentTimeMillis()
                        + " inspector image fetch " + url + " -> HTTP " + status);
                conn.disconnect();
                return null;
            }
            byte[] bytes;
            try (java.io.InputStream in = conn.getInputStream()) {
                bytes = in.readAllBytes();
            }
            conn.disconnect();
            return bytes;
        } catch (Exception ex) {
            System.out.println(System.currentTimeMillis()
                    + " inspector image fetch " + url + " failed: " + ex);
            return null;
        }
    }

    // ---- text views ----

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
        sb.append(null == t.requestBody ? "(no request body)"
                : DataUri.elideLargeBase64(Transcript.prettyIfJson(t.requestBody)));
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
            sb.append(DataUri.elideLargeBase64(Transcript.prettyIfJson(t.responseBody)));
        }
        return sb.toString();
    }

    /**
     * Find the first {@code "url": "http..."} value inside an
     * {@code image_url} object in the JSON body. Plain scanning: locate
     * {@code "image_url"}, then the next {@code "url"}, then its quoted
     * value. Returns null if the shape is not there.
     */
    private static String firstImageUrl(String body) {
        int iu = body.indexOf("\"image_url\"");
        if (iu < 0) {
            return null;
        }
        int urlKey = body.indexOf("\"url\"", iu);
        if (urlKey < 0) {
            return null;
        }
        int colon = body.indexOf(':', urlKey + 5);
        if (colon < 0) {
            return null;
        }
        int open = body.indexOf('"', colon);
        if (open < 0) {
            return null;
        }
        int close = body.indexOf('"', open + 1);
        if (close < 0) {
            return null;
        }
        String url = body.substring(open + 1, close);
        return (url.startsWith("http://") || url.startsWith("https://")) ? url : null;
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
