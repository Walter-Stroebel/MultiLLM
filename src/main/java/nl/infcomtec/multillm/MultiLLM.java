/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.multillm;

import java.io.File;
import java.util.List;

/**
 * Entry point. Loads the endpoint pool from {@code config/endpoints.json}
 * (falling back to the tracked example file when no real config exists
 * yet) and routes one prompt to whichever endpoint the {@link Router}
 * picks — proves the config-load / route / call path end to end.
 */
public final class MultiLLM {

    public static void main(String[] args) throws Exception {
        if (2 > args.length) {
            System.err.println("Usage: MultiLLM <model> <prompt...>");
            System.exit(1);
            return;
        }
        String model = args[0];
        StringBuilder prompt = new StringBuilder();
        for (int i = 1; i < args.length; i++) {
            if (0 < prompt.length()) {
                prompt.append(' ');
            }
            prompt.append(args[i]);
        }

        File configFile = new File("config/endpoints.json");
        if (!configFile.isFile()) {
            configFile = new File("config/endpoints.example.json");
        }
        List<Endpoint> endpoints = EndpointConfig.load(configFile);
        System.out.println("Loaded " + endpoints.size() + " endpoint(s) from " + configFile);

        Router router = new Router(endpoints);
        LlamaClient.Reply reply = router.route(model, prompt.toString(), false);
        System.out.println("[served by " + reply.servedBy + ", model " + reply.servedModel + "] " + reply.content);
    }
}
