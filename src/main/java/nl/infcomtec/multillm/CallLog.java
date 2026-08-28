/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.multillm;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import nl.infcomtec.nethttp.Transcript;

/**
 * A bounded in-memory ring of the most recent LLM-call {@link Transcript}s,
 * feeding the config-gated call inspector ({@link InspectorFrame}).
 * <p>
 * <b>Off by default.</b> Until {@link #enable(int)} is called (only from
 * {@code MultiLLM.main} when {@code config/endpoints.json} carries an
 * {@code "inspector"} block with {@code "enabled": true}), {@link #record}
 * is a no-op and nothing is retained. This keeps a plain production gateway
 * exactly as it was — no capture, no Swing, no retention cost, no place a
 * credential could be read from a GUI. The inspector is a D&amp;T aid, A at
 * the most, never P.
 * <p>
 * <b>Retention.</b> When enabled the ring holds at most {@code maxCalls}
 * transcripts; adding the (maxCalls+1)th evicts the oldest, which lets its
 * captured request/response body strings be collected. {@code maxCalls}
 * comes from config ({@code "inspector": { "maxCalls": N }}).
 * <p>
 * <b>Threading.</b> Every gateway request runs on its own thread (a cached
 * pool in {@link GatewayServer}), so {@link #record} is called concurrently
 * from many threads; the inspector reads via {@link #snapshot} on the Swing
 * EDT. All access to the deque is under this class's monitor — a plain
 * {@code synchronized} add/evict and a {@code synchronized} copy-out. No
 * lock-free machinery: the call rate this gateway sees is nowhere near
 * where monitor contention on a handful of short critical sections matters.
 * Listeners are notified outside the lock, and each is expected to marshal
 * onto the EDT itself.
 */
final class CallLog {

    /** One captured call: the transcript plus the routing context around it. */
    static final class Entry {

        final long at;
        final String endpointName;
        final String model;
        final Transcript transcript;

        Entry(long at, String endpointName, String model, Transcript transcript) {
            this.at = at;
            this.endpointName = endpointName;
            this.model = model;
            this.transcript = transcript;
        }
    }

    /** Notified after each new entry is retained. Implementations must not block. */
    interface Listener {

        void callRecorded(Entry entry);
    }

    private static final Object LOCK = new Object();
    private static final Deque<Entry> RING = new ArrayDeque<>();
    private static final List<Listener> LISTENERS = new ArrayList<>();

    private static volatile boolean enabled = false;
    private static volatile int maxCalls = 0;

    private CallLog() {
    }

    /**
     * Turns capture on with the given ring size. Called once at startup from
     * {@code MultiLLM.main} when the config authorises the inspector. A
     * non-positive {@code maxCalls} is clamped to 1.
     */
    static void enable(int maxCalls) {
        CallLog.maxCalls = Math.max(1, maxCalls);
        CallLog.enabled = true;
    }

    static boolean isEnabled() {
        return enabled;
    }

    /**
     * Records one call. A no-op unless {@link #enable} has been called.
     * {@code transcript} may be null (a call that failed before Rest built
     * one) — recorded as an entry with a null transcript so the inspector
     * can still show that an attempt happened.
     */
    static void record(String endpointName, String model, Transcript transcript) {
        if (!enabled) {
            return;
        }
        Entry entry = new Entry(System.currentTimeMillis(), endpointName, model, transcript);
        List<Listener> toNotify;
        synchronized (LOCK) {
            RING.addLast(entry);
            while (RING.size() > maxCalls) {
                RING.removeFirst();
            }
            toNotify = new ArrayList<>(LISTENERS);
        }
        for (Listener l : toNotify) {
            l.callRecorded(entry);
        }
    }

    /** A newest-first copy of the ring's current contents. Safe to call from any thread. */
    static List<Entry> snapshot() {
        synchronized (LOCK) {
            List<Entry> out = new ArrayList<>(RING.size());
            for (Entry e : RING) {
                out.add(0, e);
            }
            return out;
        }
    }

    static void addListener(Listener l) {
        synchronized (LOCK) {
            LISTENERS.add(l);
        }
    }
}
