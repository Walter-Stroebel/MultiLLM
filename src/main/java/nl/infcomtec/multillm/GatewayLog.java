/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.multillm;

/**
 * One-line-per-event request logging to stdout (captured by {@code
 * journalctl -u multillm} under systemd). Not a queue, not buffered —
 * this gateway is one thread per request via a cached thread pool, so
 * a plain synchronized print is all the ordering guarantee needed.
 */
final class GatewayLog {

    private GatewayLog() {
    }

    static synchronized void request(String path, String requestedModel, String servedBy, String servedModel,
            int status, long millis) {
        System.out.println(System.currentTimeMillis() + " " + path + " model=" + requestedModel
                + " servedBy=" + servedBy + " servedModel=" + servedModel + " status=" + status
                + " ms=" + millis);
    }

    static synchronized void error(String path, String requestedModel, int status, String message) {
        System.out.println(System.currentTimeMillis() + " " + path + " model=" + requestedModel
                + " status=" + status + " error=" + message);
    }

    static synchronized void cooldown(String endpointName, String reason, long cooldownMillis) {
        System.out.println(System.currentTimeMillis() + " cooldown endpoint=" + endpointName
                + " reason=" + reason + " for_ms=" + cooldownMillis);
    }
}
