package com.store.inventory;

import java.util.Arrays;

/** Bounded rolling sample of the last 4096 calls, including failures and retry time. */
public final class LatencyMetrics {
    private final long[] samples = new long[4096];
    private int size;
    private int next;
    private long calls, failures, retries, alertFailures;
    synchronized void record(long nanos, boolean failed) {
        samples[next] = nanos;
        next = (next + 1) % samples.length;
        size = Math.min(size + 1, samples.length);
        calls++;
        if (failed) failures++;
    }
    synchronized void retry() { retries++; }
    synchronized void alertFailure() { alertFailures++; }
    public synchronized Snapshot snapshot() {
        long[] copy = Arrays.copyOf(samples, size);
        Arrays.sort(copy);
        long p99 = size == 0 ? 0 : copy[(int) Math.ceil(size * .99) - 1];
        return new Snapshot(calls, failures, retries, alertFailures, size, p99 / 1_000_000.0);
    }
    public record Snapshot(long calls, long failures, long retries, long alertFailures,
                           int sampleCount, double p99Millis) { }
}
