/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.multillm;

/**
 * Sampling parameters to send alongside a chat-completion request,
 * overriding whatever defaults the backend would otherwise apply. Every
 * field is nullable and independently optional — {@link LlamaClient}
 * writes only the ones that are set, so a request built without any
 * override is byte-identical to today's plain requests. Exists to let a
 * {@link Persona} push a model into an unusual sampling regime (e.g. very
 * high temperature) without any change to which endpoint/model is asked.
 */
final class SamplingOverride {

    final Double temperature;
    final Double topP;
    final Integer topK;
    final Double minP;
    final Double repeatPenalty;

    SamplingOverride(Double temperature, Double topP, Integer topK, Double minP, Double repeatPenalty) {
        this.temperature = temperature;
        this.topP = topP;
        this.topK = topK;
        this.minP = minP;
        this.repeatPenalty = repeatPenalty;
    }
}
