/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.multillm;

import java.io.IOException;

/**
 * The endpoint itself could not be reached — connection refused, timed
 * out, or returned a 5xx — as opposed to a malformed response or a
 * client-side error, which is a real bug worth surfacing rather than a
 * transient condition worth retrying elsewhere. {@link RoutePlanner} catches
 * only this type to trigger cooldown-and-fallback.
 */
final class EndpointUnreachableException extends IOException {

    EndpointUnreachableException(String message, Throwable cause) {
        super(message, cause);
    }

    EndpointUnreachableException(String message) {
        super(message);
    }
}
