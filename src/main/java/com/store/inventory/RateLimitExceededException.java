package com.store.inventory;

import java.time.Duration;

/** An HTTP adapter can map this to 429 and Retry-After. */
public final class RateLimitExceededException extends RuntimeException {
    private final Duration retryAfter;
    public RateLimitExceededException(Duration retryAfter) {
        super("Reservation rate limit exceeded");
        this.retryAfter = retryAfter;
    }
    public Duration retryAfter() { return retryAfter; }
}
