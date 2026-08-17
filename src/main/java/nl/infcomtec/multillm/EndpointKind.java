/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.multillm;

/**
 * Whether an endpoint runs on hardware this gateway controls (a LAN box
 * running llama-server/Ollama) or is a remote third-party API. The axis
 * routing policy (local-first, local-only) actually filters on — separate
 * from {@link CostTier}, which is about money, not reachability.
 */
enum EndpointKind {
    LOCAL,
    REMOTE
}
