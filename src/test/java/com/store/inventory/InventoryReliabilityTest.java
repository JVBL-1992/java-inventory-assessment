package com.store.inventory;

import com.store.inventory.api.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.sql.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class InventoryReliabilityTest {
    static final class MutableClock extends Clock {
        private volatile Instant now = Instant.parse("2026-01-01T00:00:00Z");
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }
    private InventoryOptions options(String url, int limit, int attempts) {
        return new InventoryOptions(url, limit, Duration.ofSeconds(1), attempts,
                Duration.ofMillis(1), InventoryOptions.defaultPolicies());
    }
    private String database() { return "jdbc:h2:mem:test_" + UUID.randomUUID(); }
    private ManagedInventoryService service(Clock clock, StockAlertListener listener) {
        return Inventory.create(clock, listener, options(database(), 0, 3));
    }

    @ParameterizedTest @EnumSource(ProductCategory.class)
    void expiresAtExactBoundaryAndCannotConfirm(ProductCategory category) {
        MutableClock clock = new MutableClock();
        try (var s = service(clock, (sku, stock) -> {})) {
            s.registerProduct("sku", category); s.addStock("sku", 10);
            Reservation r = s.reserve("order", "sku", 2);
            Duration window = InventoryOptions.defaultPolicies().get(category).paymentWindow();
            assertEquals(clock.instant().plus(window), r.expiresAt());
            clock.advance(window.minusNanos(1)); assertEquals(8, s.available("sku"));
            clock.advance(Duration.ofNanos(1));
            assertThrows(IllegalStateException.class, () -> s.confirm("order"));
            assertEquals(10, s.available("sku"));
            s.expireReservations(); assertEquals(10, s.available("sku"));
            assertEquals(r, s.reserve("order", "sku", 2)); // Never renew an expired replay.
            assertEquals(10, s.available("sku"));
        }
    }

    @Test void confirmedReservationNeverReturnsAndConfirmationIsIdempotent() {
        MutableClock clock = new MutableClock();
        try (var s = service(clock, (sku, stock) -> {})) {
            s.registerProduct("sku", ProductCategory.STANDARD); s.addStock("sku", 10);
            s.reserve("order", "sku", 2); s.confirm("order"); s.confirm("order");
            clock.advance(Duration.ofDays(2)); s.confirm("order");
            assertEquals(8, s.available("sku"));
            assertThrows(IllegalStateException.class, () -> s.confirm("unknown"));
        }
    }

    @Test void flashSaleLimitAndValidationDoNotChangeStock() {
        try (var s = service(Clock.systemUTC(), (sku, stock) -> {})) {
            s.registerProduct("sku", ProductCategory.FLASH_SALE); s.addStock("sku", 10);
            assertThrows(OrderLimitExceededException.class, () -> s.reserve("order", "sku", 3));
            assertThrows(IllegalArgumentException.class, () -> s.reserve("order", "sku", 0));
            assertThrows(IllegalArgumentException.class, () -> s.addStock("sku", -1));
            assertThrows(IllegalArgumentException.class, () -> s.addStock("unknown", 1));
            assertThrows(IllegalArgumentException.class, () -> s.reserve("", "sku", 1));
            assertThrows(InsufficientStockException.class, () -> s.reserve("order", "unknown", 1));
            assertEquals(0, s.available("unknown")); assertEquals(10, s.available("sku"));
            s.registerProduct("sku", ProductCategory.FLASH_SALE);
            assertThrows(IllegalArgumentException.class, () -> s.registerProduct("sku", ProductCategory.STANDARD));
        }
    }

    @Test void idempotencyKeyAndOrderDetectConflictsWithoutDoubleDeduction() {
        try (var s = service(Clock.systemUTC(), (sku, stock) -> {})) {
            s.registerProduct("sku", ProductCategory.STANDARD); s.addStock("sku", 10);
            Reservation r = s.reserve("key", "order", "sku", 2);
            assertEquals(r, s.reserve("key", "order", "sku", 2));
            assertEquals(r, s.reserve("other-key", "order", "sku", 2));
            assertThrows(IllegalArgumentException.class, () -> s.reserve("key", "other-order", "sku", 2));
            assertThrows(IllegalArgumentException.class, () -> s.reserve("key", "order", "sku", 3));
            assertThrows(IllegalArgumentException.class, () -> s.reserve("other-key", "other-order", "sku", 2));
            assertEquals(8, s.available("sku"));
        }
    }

    @Test void lowStockAlertsResetOnlyOnReplenishment() {
        MutableClock clock = new MutableClock(); List<Integer> alerts = new ArrayList<>();
        try (var s = service(clock, (sku, stock) -> alerts.add(stock))) {
            s.registerProduct("sku", ProductCategory.STANDARD); s.addStock("sku", 10);
            s.reserve("one", "sku", 5); s.reserve("two", "sku", 1);
            s.available("sku"); s.dispatchAlerts();
            assertEquals(List.of(5), alerts);
            clock.advance(Duration.ofMinutes(15)); assertEquals(10, s.available("sku"));
            s.reserve("three", "sku", 6); assertEquals(List.of(5), alerts);
            s.addStock("sku", 10); s.reserve("four", "sku", 9);
            assertEquals(List.of(5, 5), alerts);
        }
    }

    @Test void failedAlertSurvivesRestartAndDoesNotFailReservation() {
        String url = database(); AtomicInteger failures = new AtomicInteger();
        try (var s = Inventory.create(Clock.systemUTC(), (sku, stock) -> {
                    failures.incrementAndGet(); throw new IllegalStateException("mail unavailable");
                }, options(url, 0, 3));
             var other = Inventory.create(Clock.systemUTC(), (sku, stock) -> failures.incrementAndGet(), options(url, 0, 3))) {
            s.registerProduct("sku", ProductCategory.STANDARD); s.addStock("sku", 10);
            s.reserve("order", "sku", 5);
            assertEquals(5, s.available("sku")); assertEquals(1, s.metrics().alertFailures());
            s.close(); other.dispatchAlerts(); other.dispatchAlerts();
            assertEquals(2, failures.get());
        }
    }

    @Test void rateLimitIsSharedAcrossInstancesAndResetsAtBoundary() {
        MutableClock clock = new MutableClock(); String url = database();
        try (var first = Inventory.create(clock, (sku, stock) -> {}, options(url, 2, 3));
             var second = Inventory.create(clock, (sku, stock) -> {}, options(url, 2, 3))) {
            first.registerProduct("sku", ProductCategory.STANDARD); first.addStock("sku", 10);
            first.reserve("one", "sku", 1); second.reserve("two", "sku", 1);
            var e = assertThrows(RateLimitExceededException.class, () -> first.reserve("three", "sku", 1));
            assertEquals(Duration.ofSeconds(1), e.retryAfter()); assertEquals(8, second.available("sku"));
            clock.advance(Duration.ofSeconds(1)); second.reserve("three", "sku", 1);
            assertEquals(7, first.available("sku"));
        }
    }

    @Test void concurrentInstancesNeverOversell() throws Exception {
        String url = database();
        try (var first = Inventory.create(Clock.systemUTC(), (sku, stock) -> {}, options(url, 0, 3));
             var second = Inventory.create(Clock.systemUTC(), (sku, stock) -> {}, options(url, 0, 3));
             var pool = Executors.newFixedThreadPool(16)) {
            first.registerProduct("sku", ProductCategory.STANDARD); first.addStock("sku", 25);
            CountDownLatch start = new CountDownLatch(1); List<Future<Boolean>> jobs = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                int id = i;
                jobs.add(pool.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    try { (id % 2 == 0 ? first : second).reserve("order-" + id, "sku", 1); return true; }
                    catch (InsufficientStockException e) { return false; }
                }));
            }
            start.countDown(); int success = 0;
            for (var job : jobs) if (job.get(20, TimeUnit.SECONDS)) success++;
            assertEquals(25, success); assertEquals(0, first.available("sku"));
            System.out.printf("Concurrent workload: calls=%d, first-instance p99=%.3f ms, second-instance p99=%.3f ms%n",
                    first.metrics().calls() + second.metrics().calls(), first.metrics().p99Millis(), second.metrics().p99Millis());
        }
    }

    @Test void concurrentDuplicateRequestsAcrossInstancesDeductOnce() throws Exception {
        String url = database();
        try (var first = Inventory.create(Clock.systemUTC(), (sku, stock) -> {}, options(url, 0, 3));
             var second = Inventory.create(Clock.systemUTC(), (sku, stock) -> {}, options(url, 0, 3));
             var pool = Executors.newFixedThreadPool(8)) {
            first.registerProduct("sku", ProductCategory.STANDARD); first.addStock("sku", 20);
            List<Callable<Reservation>> jobs = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                var s = i % 2 == 0 ? first : second;
                jobs.add(() -> s.reserve("key", "order", "sku", 3));
            }
            Reservation expected = null;
            for (var job : pool.invokeAll(jobs)) {
                Reservation actual = job.get(); if (expected == null) expected = actual;
                assertEquals(expected, actual);
            }
            assertEquals(17, first.available("sku"));
        }
    }

    @Test void databaseLockTimeoutIsRetriedThenSucceeds() throws Exception {
        String url = database() + ";LOCK_TIMEOUT=15";
        try (var s = Inventory.create(Clock.systemUTC(), (sku, stock) -> {}, options(url, 0, 10));
             var lock = DriverManager.getConnection(url); var pool = Executors.newSingleThreadExecutor()) {
            s.registerProduct("sku", ProductCategory.STANDARD); s.addStock("sku", 10);
            lock.setAutoCommit(false);
            try (var statement = lock.createStatement(); var rows = statement.executeQuery("SELECT id FROM inventory_lock WHERE id = 1 FOR UPDATE")) {
                assertTrue(rows.next());
                var job = pool.submit(() -> s.reserve("order", "sku", 2));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (s.metrics().retries() == 0 && System.nanoTime() < deadline) Thread.sleep(2);
                assertTrue(s.metrics().retries() > 0); lock.commit();
                assertEquals(2, job.get(5, TimeUnit.SECONDS).quantity());
                assertEquals(8, s.available("sku"));
            } finally { lock.rollback(); }
        }
    }

    @Test void retriesAreBoundedAndBusinessFailuresAreNotRetried() throws Exception {
        String url = database() + ";LOCK_TIMEOUT=5";
        try (var s = Inventory.create(Clock.systemUTC(), (sku, stock) -> {}, options(url, 0, 2));
             var lock = DriverManager.getConnection(url)) {
            s.registerProduct("sku", ProductCategory.STANDARD);
            assertThrows(InsufficientStockException.class, () -> s.reserve("order", "sku", 1));
            assertEquals(0, s.metrics().retries());
            lock.setAutoCommit(false);
            try (var statement = lock.createStatement(); var rows = statement.executeQuery("SELECT id FROM inventory_lock WHERE id = 1 FOR UPDATE")) {
                assertTrue(rows.next()); assertThrows(IllegalStateException.class, () -> s.available("sku"));
                assertEquals(1, s.metrics().retries());
            } finally { lock.rollback(); }
        }
    }

    @Test void p99UsesNearestRankAndBoundedSamples() {
        LatencyMetrics metrics = new LatencyMetrics();
        assertEquals(0, metrics.snapshot().p99Millis());
        for (int i = 1; i <= 100; i++) metrics.record(i * 1_000_000L, false);
        assertEquals(99, metrics.snapshot().p99Millis());
        for (int i = 0; i < 5000; i++) metrics.record(1_000_000L, true);
        assertEquals(4096, metrics.snapshot().sampleCount()); assertEquals(1, metrics.snapshot().p99Millis());
        assertEquals(5100, metrics.snapshot().calls()); assertEquals(5000, metrics.snapshot().failures());
    }

    @Test void customPoliciesAndOverflowAreSupported() {
        var policies = new EnumMap<ProductCategory, CategoryPolicy>(InventoryOptions.defaultPolicies());
        policies.put(ProductCategory.STANDARD, new CategoryPolicy(Duration.ofSeconds(3), 1));
        MutableClock clock = new MutableClock();
        try (var s = Inventory.create(clock, (sku, stock) -> {}, new InventoryOptions(database(), 0,
                Duration.ofSeconds(1), 3, Duration.ZERO, policies))) {
            s.registerProduct("sku", ProductCategory.STANDARD); s.addStock("sku", Integer.MAX_VALUE);
            assertThrows(ArithmeticException.class, () -> s.addStock("sku", 1));
            assertThrows(OrderLimitExceededException.class, () -> s.reserve("order", "sku", 2));
            assertEquals(clock.instant().plusSeconds(3), s.reserve("order", "sku", 1).expiresAt());
        }
    }

    @Test void replenishmentCannotOverflowWhenActiveUnitsAreReturned() {
        MutableClock clock = new MutableClock();
        try (var s = service(clock, (sku, stock) -> {})) {
            s.registerProduct("sku", ProductCategory.STANDARD); s.addStock("sku", Integer.MAX_VALUE);
            s.reserve("order", "sku", 10);
            assertThrows(ArithmeticException.class, () -> s.addStock("sku", 1));
            clock.advance(Duration.ofMinutes(15));
            assertEquals(Integer.MAX_VALUE, s.available("sku"));
        }
    }

    @Test void fileDatabasePreservesIdempotencyAndOutboxAfterFullRestart(@TempDir Path directory) {
        String url = "jdbc:h2:" + directory.resolve("inventory").toAbsolutePath().toString().replace('\\', '/');
        MutableClock clock = new MutableClock(); Reservation original;
        try (var s = Inventory.create(clock, (sku, stock) -> { throw new IllegalStateException("offline"); }, options(url, 0, 3))) {
            s.registerProduct("sku", ProductCategory.STANDARD); s.addStock("sku", 10);
            original = s.reserve("key", "order", "sku", 5);
        }
        AtomicInteger alerts = new AtomicInteger();
        try (var s = Inventory.create(clock, (sku, stock) -> alerts.incrementAndGet(), options(url, 0, 3))) {
            assertEquals(5, s.available("sku"));
            assertEquals(original, s.reserve("key", "order", "sku", 5));
            assertEquals(5, s.available("sku")); assertEquals(1, alerts.get());
            s.dispatchAlerts(); assertEquals(1, alerts.get());
            assertThrows(IllegalArgumentException.class, () -> s.reserve("key", "other-order", "sku", 5));
        }
    }

    @Test void concurrentRateLimitingAdmitsExactlyTheConfiguredQuota() throws Exception {
        String url = database(); MutableClock clock = new MutableClock();
        try (var first = Inventory.create(clock, (sku, stock) -> {}, options(url, 10, 3));
             var second = Inventory.create(clock, (sku, stock) -> {}, options(url, 10, 3));
             var pool = Executors.newFixedThreadPool(8)) {
            first.registerProduct("sku", ProductCategory.STANDARD); first.addStock("sku", 100);
            List<Callable<Boolean>> jobs = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                int id = i; var s = i % 2 == 0 ? first : second;
                jobs.add(() -> {
                    try { s.reserve("order-" + id, "sku", 1); return true; }
                    catch (RateLimitExceededException e) { return false; }
                });
            }
            int admitted = 0;
            for (var job : pool.invokeAll(jobs)) if (job.get()) admitted++;
            assertEquals(10, admitted); assertEquals(90, first.available("sku"));
        }
    }
}
