package com.store.inventory;

import com.store.inventory.api.InventoryService;
import com.store.inventory.api.Reservation;

public interface ManagedInventoryService extends InventoryService, AutoCloseable {
    Reservation reserve(String idempotencyKey, String orderId, String sku, int quantity);
    void expireReservations();
    void dispatchAlerts();
    LatencyMetrics.Snapshot metrics();
    @Override void close();
}
