package com.store.inventory;

import com.store.inventory.api.InventoryService;
import com.store.inventory.api.StockAlertListener;
import java.time.Clock;

/**
 * Entry point used by our automated tests. Keep this signature exactly as it is,
 * and build your implementation here.
 */
public final class Inventory {

    private Inventory() {
    }

    public static InventoryService create(Clock clock, StockAlertListener alertListener) {
        return create(clock, alertListener, InventoryOptions.defaults());
    }

    public static ManagedInventoryService create(Clock clock, StockAlertListener alertListener,
                                                  InventoryOptions options) {
        return new JdbcInventoryService(clock, alertListener, options);
    }
}
