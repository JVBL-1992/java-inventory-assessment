package com.store.inventory;

import java.time.Duration;
import java.util.Objects;

public record CategoryPolicy(Duration paymentWindow, int orderLimit) {
    public CategoryPolicy {
        Objects.requireNonNull(paymentWindow);
        if (paymentWindow.isNegative() || paymentWindow.isZero() || orderLimit < 1)
            throw new IllegalArgumentException("Positive payment window and order limit required");
    }
}
