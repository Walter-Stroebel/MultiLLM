/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.multillm;

/**
 * A named alias that resolves to one exact endpoint+model plus a fixed
 * {@link SamplingOverride} — e.g. {@code "drunk-gemma4"} pointing at
 * predator's {@code gemma-vision} with temperature cranked way up. A
 * persona names one backend outright, the same way {@code host/model}
 * addressing does: no policy ordering, no fallback among candidates,
 * since the whole point is to ask *that* model, sampled *that* way, and
 * see what comes back.
 */
final class Persona {

    final String name;
    final String hostEndpoint;
    final String model;
    final SamplingOverride sampling;

    Persona(String name, String hostEndpoint, String model, SamplingOverride sampling) {
        this.name = name;
        this.hostEndpoint = hostEndpoint;
        this.model = model;
        this.sampling = sampling;
    }
}
