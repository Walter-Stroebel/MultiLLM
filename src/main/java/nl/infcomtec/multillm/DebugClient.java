/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.multillm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridBagLayout;
import java.awt.event.ActionEvent;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.WindowConstants;
import nl.infcomtec.advswing.ACheckBox;
import nl.infcomtec.advswing.ADropDown;
import nl.infcomtec.advswing.ALabel;
import nl.infcomtec.advswing.AButton;
import nl.infcomtec.advswing.ATextArea;
import nl.infcomtec.advswing.EzAction;
import nl.infcomtec.advswing.GBC;

/**
 * Manual-poke Swing debug client for the MultiLLM gateway itself (not for
 * any single backend directly). Loads {@code config/endpoints.json}
 * (falling back to {@code config/endpoints.example.json}) with the same
 * {@link EndpointConfig}/{@link Endpoint} classes the gateway uses, purely
 * to populate the endpoint/model dropdowns — every actual chat/upload
 * request goes to the gateway's own HTTP API on {@code localhost:8085} (or
 * whatever port is passed as the first CLI arg), never straight to a
 * backend, since the point of this tool is to exercise the gateway's own
 * routing/files/streaming contract.
 */
public final class DebugClient extends JFrame {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String gatewayBaseUrl;
    private final List<Endpoint> endpoints;

    private final ADropDown endpointDropDown;
    private final ADropDown modelDropDown;
    private final ATextArea promptArea = new ATextArea(5, 60);
    private final ATextArea outputArea = new ATextArea(20, 60);
    private final ACheckBox jsonModeCheck = new ACheckBox();
    private final ACheckBox streamCheck = new ACheckBox();
    private final JLabel imageLabel = new JLabel("(no image)");
    private final JLabel statusLabel = new JLabel(" ");

    private String attachedImageUrl;

    public static void main(String[] args) throws IOException {
        int port = 8085;
        if (0 < args.length) {
            port = Integer.parseInt(args[0]);
        }
        String gatewayBaseUrl = "http://localhost:" + port;

        File configFile = new File("config/endpoints.json");
        if (!configFile.isFile()) {
            configFile = new File("config/endpoints.example.json");
        }
        List<Endpoint> endpoints = EndpointConfig.load(configFile);

        final String base = gatewayBaseUrl;
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                new DebugClient(base, endpoints).setVisible(true);
            }
        });
    }

    private DebugClient(String gatewayBaseUrl, List<Endpoint> endpoints) {
        super("MultiLLM Debug Client — " + gatewayBaseUrl);
        this.gatewayBaseUrl = gatewayBaseUrl;
        this.endpoints = endpoints;

        String[] endpointNames = new String[endpoints.size()];
        for (int i = 0; i < endpoints.size(); i++) {
            endpointNames[i] = endpoints.get(i).name;
        }

        modelDropDown = new ADropDown(new String[0]) {
            @Override
            public void itemAdded(String item) {
            }

            @Override
            public void itemSelected(String item) {
            }
        };

        endpointDropDown = new ADropDown(endpointNames) {
            @Override
            public void itemAdded(String item) {
            }

            @Override
            public void itemSelected(String item) {
                populateModels(item);
            }
        };

        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout());
        add(buildTopPanel(), BorderLayout.NORTH);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                new JScrollPane(promptArea), new JScrollPane(outputArea));
        split.setResizeWeight(0.3);
        add(split, BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);

        if (0 < endpoints.size()) {
            populateModels(endpoints.get(0).name);
        }

        setPreferredSize(new Dimension(900, 700));
        pack();
        setLocationRelativeTo(null);
    }

    private JPanel buildTopPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        GBC gbc = new GBC().withCols(4);

        panel.add(new ALabel("Endpoint:"), gbc.next());
        panel.add(endpointDropDown, gbc.next());
        panel.add(new ALabel("Model:"), gbc.next());
        panel.add(modelDropDown, gbc.next());

        panel.add(new ALabel("JSON mode:"), gbc.next());
        panel.add(jsonModeCheck, gbc.next());
        panel.add(new ALabel("Streaming:"), gbc.next());
        panel.add(streamCheck, gbc.next());

        panel.add(new AButton(new EzAction("Attach image...") {
            @Override
            public void actionPerformed(ActionEvent e) {
                attachImage();
            }
        }), gbc.next());
        panel.add(imageLabel, gbc.next());
        panel.add(new AButton(new EzAction("Send") {
            @Override
            public void actionPerformed(ActionEvent e) {
                send();
            }
        }), gbc.next());
        panel.add(new JLabel(), gbc.next());

        return panel;
    }

    private void populateModels(String endpointName) {
        modelDropDown.removeAllItems();
        for (Endpoint ep : endpoints) {
            if (ep.name.equals(endpointName)) {
                for (String model : ep.models) {
                    modelDropDown.addItem(model);
                }
                break;
            }
        }
    }

    private void attachImage() {
        JFileChooser chooser = new JFileChooser();
        if (JFileChooser.APPROVE_OPTION != chooser.showOpenDialog(this)) {
            return;
        }
        File file = chooser.getSelectedFile();
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                return uploadFile(file);
            }

            @Override
            protected void done() {
                try {
                    attachedImageUrl = get();
                    imageLabel.setText(file.getName());
                } catch (Exception ex) {
                    statusLabel.setText("Upload failed: " + ex.getMessage());
                }
            }
        }.execute();
    }

    private String uploadFile(File file) throws IOException {
        byte[] bytes = Files.readAllBytes(file.toPath());
        String mimeType = URLConnection.guessContentTypeFromName(file.getName());
        if (null == mimeType) {
            mimeType = "application/octet-stream";
        }
        URI uri = URI.create(gatewayBaseUrl + "/v1/files");
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        conn.setRequestMethod("PUT");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", mimeType);
        try (OutputStream out = conn.getOutputStream()) {
            out.write(bytes);
        }
        int status = conn.getResponseCode();
        String body = readAll(200 <= status && status < 300 ? conn.getInputStream() : conn.getErrorStream());
        conn.disconnect();
        if (200 > status || status >= 300) {
            throw new IOException("Upload failed: HTTP " + status + " " + body);
        }
        return MAPPER.readTree(body).get("url").asText();
    }

    private void send() {
        String endpointName = (String) endpointDropDown.getSelectedItem();
        String modelName = (String) modelDropDown.getSelectedItem();
        if (null == endpointName || null == modelName) {
            statusLabel.setText("Pick an endpoint and model first.");
            return;
        }
        String model = endpointName + "/" + modelName;
        String prompt = promptArea.getText();
        boolean json = jsonModeCheck.isSelected();
        boolean stream = streamCheck.isSelected();
        String imageUrl = attachedImageUrl;

        outputArea.setText("");
        statusLabel.setText("Sending...");

        new SwingWorker<Void, String>() {
            private long start;
            private int httpStatus;
            private String servedBy;

            @Override
            protected Void doInBackground() throws Exception {
                start = System.currentTimeMillis();
                byte[] body = buildRequestBody(model, prompt, json, stream, imageUrl);
                URI uri = URI.create(gatewayBaseUrl + "/v1/chat/completions");
                HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(body);
                }
                httpStatus = conn.getResponseCode();
                InputStream in = (200 <= httpStatus && httpStatus < 300) ? conn.getInputStream() : conn.getErrorStream();

                if (200 != httpStatus) {
                    String errBody = readAll(in);
                    conn.disconnect();
                    JsonNode err = MAPPER.readTree(errBody);
                    throw new IOException(err.has("error") ? err.get("error").get("message").asText() : errBody);
                }

                if (stream) {
                    String header = conn.getHeaderField("X-Served-By");
                    servedBy = null != header ? header : "?";
                    readSse(in);
                } else {
                    String responseBody = readAll(in);
                    JsonNode root = MAPPER.readTree(responseBody);
                    String content = root.get("choices").get(0).get("message").get("content").asText();
                    publish(content);
                    servedBy = root.has("served_by") ? root.get("served_by").asText() : "?";
                }
                conn.disconnect();
                return null;
            }

            private void readSse(InputStream in) throws IOException {
                BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                String line;
                while (null != (line = reader.readLine())) {
                    if (!line.startsWith("data: ")) {
                        continue;
                    }
                    String data = line.substring("data: ".length());
                    if ("[DONE]".equals(data)) {
                        break;
                    }
                    JsonNode chunk = MAPPER.readTree(data);
                    JsonNode choices = chunk.get("choices");
                    if (null != choices && 0 < choices.size()) {
                        JsonNode delta = choices.get(0).get("delta");
                        if (null != delta && delta.hasNonNull("content")) {
                            publish(delta.get("content").asText());
                        }
                    }
                }
            }

            @Override
            protected void process(List<String> chunks) {
                for (String chunk : chunks) {
                    outputArea.append(chunk);
                }
            }

            @Override
            protected void done() {
                long elapsed = System.currentTimeMillis() - start;
                try {
                    get();
                    statusLabel.setText("HTTP " + httpStatus + " | served_by=" + servedBy + " | " + elapsed + "ms");
                } catch (Exception ex) {
                    Throwable cause = ex.getCause();
                    statusLabel.setText("Error: " + (null == cause ? ex.getMessage() : cause.getMessage()));
                }
            }
        }.execute();
    }

    private static byte[] buildRequestBody(String model, String prompt, boolean json, boolean stream, String imageUrl)
            throws IOException {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        ObjectNode message = MAPPER.createObjectNode();
        message.put("role", "user");
        if (null == imageUrl) {
            message.put("content", prompt);
        } else {
            var content = message.putArray("content");
            ObjectNode textPart = content.addObject();
            textPart.put("type", "text");
            textPart.put("text", prompt);
            ObjectNode imagePart = content.addObject();
            imagePart.put("type", "image_url");
            imagePart.putObject("image_url").put("url", imageUrl);
        }
        body.putArray("messages").add(message);
        if (json) {
            body.putObject("response_format").put("type", "json_object");
        }
        if (stream) {
            body.put("stream", true);
        }
        return MAPPER.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
    }

    private static String readAll(InputStream in) throws IOException {
        if (null == in) {
            return "";
        }
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while (-1 != (n = in.read(chunk))) {
            buf.write(chunk, 0, n);
        }
        return buf.toString(StandardCharsets.UTF_8);
    }
}
