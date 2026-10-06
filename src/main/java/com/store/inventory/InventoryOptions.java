package com.store.inventory;

import com.store.inventory.api.ProductCategory;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Instances sharing the same JDBC URL share inventory, idempotency and rate limits. */
public record InventoryOptions(String jdbcUrl, int requestsPerWindow, Duration rateWindow,
                               int maxAttempts, Duration retryBaseDelay,
                               Map<ProductCategory, CategoryPolicy> policies) {
    public InventoryOptions {
        Objects.requireNonNull(jdbcUrl);
        Objects.requireNonNull(rateWindow);
        Objects.requireNonNull(retryBaseDelay);
        policies = Map.copyOf(policies);
        if (jdbcUrl.isBlank() || requestsPerWindow < 0 || rateWindow.toMillis() < 1
                || maxAttempts < 1 || maxAttempts > 10 || retryBaseDelay.isNegative()
                || retryBaseDelay.toMillis() > 1000)
            throw new IllegalArgumentException("Invalid inventory configuration");
        for (var category : ProductCategory.values())
            if (!policies.containsKey(category)) throw new IllegalArgumentException("Missing policy: " + category);
    }

    public static InventoryOptions defaults() {
        return new InventoryOptions("jdbc:h2:mem:inventory_" + UUID.randomUUID(),
                0, Duration.ofSeconds(1), 3, Duration.ofMillis(10), defaultPolicies());
    }

    public static Map<ProductCategory, CategoryPolicy> defaultPolicies() {
        return Map.of(ProductCategory.STANDARD, new CategoryPolicy(Duration.ofMinutes(15), Integer.MAX_VALUE),
                ProductCategory.PRE_ORDER, new CategoryPolicy(Duration.ofHours(24), Integer.MAX_VALUE),
                ProductCategory.FLASH_SALE, new CategoryPolicy(Duration.ofMinutes(5), 2));
    }
}
