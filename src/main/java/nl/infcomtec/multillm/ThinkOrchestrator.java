/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.multillm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Runs the "help me think about X" two-pass pattern behind
 * {@code POST /v1/think}: a divergent {@link Persona} (loose sampling,
 * expected to fabulate) generates raw material, then a convergent
 * {@link Persona} (tight sampling) is asked to sort that material into
 * genuine insight versus confident fabrication — told explicitly what it
 * is looking at (which persona produced it, and that persona's sampling
 * regime), rather than asked to detect blind. Both persona resolutions
 * reuse the same one-endpoint-only semantics as {@link RoutePlanner}'s
 * persona handling; this class does not touch normal candidate routing.
 */
final class ThinkOrchestrator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String JUDGE_INSTRUCTIONS = """
            You are reviewing raw output from another language model that was deliberately run \
            with loosened sampling settings (persona "%s": temperature %s, top_p %s, top_k %s, \
            min_p %s, repeat_penalty %s) to encourage free, high-variance generation. That \
            model is known to stay fluent and grammatical but sometimes invents plausible-\
            sounding technical terminology, named concepts, or formulas that do not actually \
            exist, presented with full confidence.

            Read its raw output below and identify the distinct claims, terms, or ideas in it. \
            For each one, decide whether it looks like genuine insight (a real, checkable idea \
            the sampling regime surfaced) or confident fabrication (invented jargon or a false \
            claim dressed up to sound authoritative), and give brief reasoning for that verdict.

            Respond with a JSON object of the shape:
            {"items": [{"claim": "...", "verdict": "insight" or "fabrication", "reasoning": "..."}], \
            "summary": "..."}
            where "summary" is one or two sentences on the overall output.

            Raw output to review:
            %s
            """;

    private final List<Endpoint> endpoints;
    private final Map<String, Persona> personas;

    ThinkOrchestrator(List<Endpoint> endpoints, Map<String, Persona> personas) {
        this.endpoints = endpoints;
        this.personas = personas;
    }

    static final class DivergentResult {

        final String persona;
        final String servedBy;
        final String content;

        DivergentResult(String persona, String servedBy, String content) {
            this.persona = persona;
            this.servedBy = servedBy;
            this.content = content;
        }
    }

    static final class ConvergentItem {

        final String claim;
        final String verdict;
        final String reasoning;

        ConvergentItem(String claim, String verdict, String reasoning) {
            this.claim = claim;
            this.verdict = verdict;
            this.reasoning = reasoning;
        }
    }

    static final class ConvergentResult {

        final String persona;
        final String servedBy;
        final List<ConvergentItem> items;
        final String summary;

        ConvergentResult(String persona, String servedBy, List<ConvergentItem> items, String summary) {
            this.persona = persona;
            this.servedBy = servedBy;
            this.items = items;
            this.summary = summary;
        }
    }

    static final class ThinkReply {

        final DivergentResult divergent;
        final ConvergentResult convergent;
        final String convergentError;

        ThinkReply(DivergentResult divergent, ConvergentResult convergent, String convergentError) {
            this.divergent = divergent;
            this.convergent = convergent;
            this.convergentError = convergentError;
        }
    }

    ThinkReply run(ThinkRequest request) throws IOException, InterruptedException {
        Persona divergentPersona = requirePersona(request.divergentPersona);
        Endpoint divergentEndpoint = requireEndpoint(divergentPersona);

        LlamaClient.Reply divergentReply = LlamaClient.ask(divergentEndpoint, divergentPersona.model,
                request.prompt, null, false, null, null, divergentPersona.sampling);
        DivergentResult divergent = new DivergentResult(divergentPersona.name, divergentReply.servedBy,
                divergentReply.content);

        try {
            Persona convergentPersona = requirePersona(request.convergentPersona);
            Endpoint convergentEndpoint = requireEndpoint(convergentPersona);

            String judgePrompt = buildJudgePrompt(divergentPersona, divergentReply.content);
            LlamaClient.Reply convergentReply = LlamaClient.ask(convergentEndpoint, convergentPersona.model,
                    judgePrompt, null, true, null, null, convergentPersona.sampling);

            ConvergentResult convergent = parseConvergent(convergentPersona.name, convergentReply.servedBy,
                    convergentReply.content);
            return new ThinkReply(divergent, convergent, null);
        } catch (IOException | InterruptedException e) {
            return new ThinkReply(divergent, null, e.getMessage());
        }
    }

    private String buildJudgePrompt(Persona divergentPersona, String divergentContent) {
        SamplingOverride s = divergentPersona.sampling;
        return String.format(JUDGE_INSTRUCTIONS, divergentPersona.name,
                null == s || null == s.temperature ? "default" : s.temperature,
                null == s || null == s.topP ? "default" : s.topP,
                null == s || null == s.topK ? "default" : s.topK,
                null == s || null == s.minP ? "default" : s.minP,
                null == s || null == s.repeatPenalty ? "default" : s.repeatPenalty,
                divergentContent);
    }

    private ConvergentResult parseConvergent(String personaName, String servedBy, String content) {
        try {
            JsonNode root = MAPPER.readTree(content);
            List<ConvergentItem> items = new ArrayList<>();
            JsonNode itemsNode = root.get("items");
            if (null != itemsNode && itemsNode.isArray()) {
                for (JsonNode item : itemsNode) {
                    items.add(new ConvergentItem(
                            item.path("claim").asText(""),
                            item.path("verdict").asText(""),
                            item.path("reasoning").asText("")));
                }
            }
            String summary = root.path("summary").asText("");
            return new ConvergentResult(personaName, servedBy, items, summary);
        } catch (IOException e) {
            // The judge's reply didn't parse as the requested JSON shape — a real,
            // survivable outcome (not every model obeys response_format reliably
            // under an unusual prompt), not grounds to fail a request whose
            // divergent pass already succeeded. Fall back to the raw text.
            return new ConvergentResult(personaName, servedBy, List.of(), content);
        }
    }

    private Persona requirePersona(String name) throws IOException {
        Persona persona = personas.get(name);
        if (null == persona) {
            throw new IOException("No such persona: " + name);
        }
        return persona;
    }

    private Endpoint requireEndpoint(Persona persona) throws IOException {
        for (Endpoint e : endpoints) {
            if (e.name.equals(persona.hostEndpoint)) {
                return e;
            }
        }
        throw new IOException("Persona " + persona.name + " names unknown endpoint " + persona.hostEndpoint);
    }
}
