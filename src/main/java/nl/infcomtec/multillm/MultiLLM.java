/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.multillm;

import java.io.File;
import java.util.List;
import java.util.Map;
import nl.infcomtec.nethttp.Rest;

/**
 * Entry point. Loads the endpoint pool from {@code config/endpoints.json}
 * (falling back to the tracked example file when no real config exists
 * yet), an optional {@code config/personas.json} of named sampling-override
 * aliases (e.g. {@code "drunk-gemma4"}), and starts the gateway server: an
 * OpenRouter-alike {@code /v1/chat/completions} that prefers local
 * endpoints, plus a {@code /v1/files} upload path for images.
 */
public final class MultiLLM {

    private static final int DEFAULT_PORT = 8085;

    public static void main(String[] args) throws Exception {
        int port = DEFAULT_PORT;
        if (0 < args.length) {
            port = Integer.parseInt(args[0]);
        }

        File configFile = new File("config/endpoints.json");
        if (!configFile.isFile()) {
            configFile = new File("config/endpoints.example.json");
        }
        List<Endpoint> endpoints = EndpointConfig.load(configFile);
        System.out.println("Loaded " + endpoints.size() + " endpoint(s) from " + configFile);

        InspectorConfig inspector = EndpointConfig.loadInspector(configFile);
        if (inspector.enabled) {
            Rest.revealSecretsInTranscript = inspector.revealSecrets;
            CallLog.enable(inspector.maxCalls);
            System.out.println("INSPECTOR ENABLED — the last " + inspector.maxCalls
                    + " LLM calls (assembled URL, headers, request/response bodies"
                    + (inspector.revealSecrets ? ", credentials IN CLEAR" : ", credentials masked")
                    + ") are visible in a local GUI window. This is a D&T/A feature, never P.");
            InspectorFrame.launch(inspector.revealSecrets);
        }

        Map<String, Persona> personas = EndpointConfig.loadPersonas(new File("config/personas.json"));
        if (!personas.isEmpty()) {
            System.out.println("Loaded " + personas.size() + " persona(s): " + personas.keySet());
        }

        String selfBaseUrl = "http://" + java.net.InetAddress.getLocalHost().getHostName() + ":" + port;
        GatewayServer server = new GatewayServer(endpoints, personas, port, selfBaseUrl);
        server.start();
        System.out.println("MultiLLM gateway listening on port " + port + " (self URL " + selfBaseUrl + ")");
    }
}
