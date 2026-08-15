/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.multillm;

/**
 * Coarse, user-declared cost bucket for an endpoint. MultiLLM cannot
 * observe money spent from the wire the way it observes latency or
 * tokens/second, so cost is a hard gate the user states up front, not a
 * measured score: free/local endpoints are always preferred, cheap is
 * used when no free endpoint can serve the request, and expensive
 * requires the caller to opt in explicitly.
 */
enum CostTier {
    FREE,
    CHEAP,
    EXPENSIVE
}
