package com.store.inventory;

import com.store.inventory.api.*;
import java.sql.*;
import java.time.*;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/** All mutations lock the same database row, including operations from other instances. */
final class JdbcInventoryService implements ManagedInventoryService {
    private final Clock clock;
    private final StockAlertListener listener;
    private final InventoryOptions options;
    private final LatencyMetrics metrics = new LatencyMetrics();
    private final Connection keeper;
    private volatile boolean closed;

    JdbcInventoryService(Clock clock, StockAlertListener listener, InventoryOptions options) {
        this.clock = Objects.requireNonNull(clock);
        this.listener = Objects.requireNonNull(listener);
        this.options = Objects.requireNonNull(options);
        try {
            keeper = DriverManager.getConnection(options.jdbcUrl());
            initialize();
        } catch (SQLException e) { throw new IllegalStateException("Cannot initialize inventory database", e); }
    }

    private void initialize() throws SQLException {
        try (Statement s = keeper.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS inventory_lock (id INT PRIMARY KEY)");
            // Insert only when missing: unlike MERGE this never overwrites an existing lock row.
            try { s.execute("INSERT INTO inventory_lock VALUES (1)"); }
            catch (SQLException e) { if (!"23505".equals(e.getSQLState())) throw e; }
            s.execute("CREATE TABLE IF NOT EXISTS products (sku VARCHAR(200) PRIMARY KEY, category VARCHAR(40) NOT NULL, stock INT NOT NULL CHECK(stock >= 0), alerted BOOLEAN NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS reservations (order_id VARCHAR(200) PRIMARY KEY, sku VARCHAR(200) NOT NULL REFERENCES products(sku), quantity INT NOT NULL CHECK(quantity > 0), expires_at TIMESTAMP(9) WITH TIME ZONE NOT NULL, status VARCHAR(20) NOT NULL CHECK(status IN ('ACTIVE', 'CONFIRMED', 'EXPIRED')))");
            s.execute("CREATE INDEX IF NOT EXISTS active_expiry ON reservations(status, expires_at)");
            s.execute("CREATE TABLE IF NOT EXISTS idempotency_keys (request_key VARCHAR(200) PRIMARY KEY, order_id VARCHAR(200) NOT NULL REFERENCES reservations(order_id))");
            s.execute("CREATE TABLE IF NOT EXISTS rate_bucket (id INT PRIMARY KEY, window_start TIMESTAMP(9) WITH TIME ZONE NOT NULL, used INT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS alert_outbox (id VARCHAR(36) PRIMARY KEY, sku VARCHAR(200) NOT NULL, available INT NOT NULL, lease_until TIMESTAMP(9) WITH TIME ZONE, claim_token VARCHAR(36))");
        }
    }

    @Override public void registerProduct(String sku, ProductCategory category) {
        measured(() -> {
            text(sku, "sku"); Objects.requireNonNull(category, "category");
            transaction(c -> {
                try (var s = statement(c, "SELECT category FROM products WHERE sku = ?", sku); var r = s.executeQuery()) {
                    if (r.next()) {
                        if (!category.name().equals(r.getString(1)))
                            throw new IllegalArgumentException("Product already registered with another category");
                    } else update(c, "INSERT INTO products VALUES (?, ?, 0, FALSE)", sku, category.name());
                }
                return null;
            });
            return null;
        });
    }

    @Override public void addStock(String sku, int quantity) {
        measured(() -> {
            text(sku, "sku"); positive(quantity);
            transaction(c -> {
                expire(c);
                Product p = product(c, sku);
                if (p == null) throw new IllegalArgumentException("Unknown product: " + sku);
                int stock = Math.addExact(p.stock(), quantity);
                // Returned reservations must still fit after any later expiration.
                try (var s = statement(c, "SELECT COALESCE(SUM(quantity), 0) FROM reservations WHERE sku = ? AND status = 'ACTIVE'", sku);
                     var r = s.executeQuery()) {
                    r.next();
                    if (stock + r.getLong(1) > Integer.MAX_VALUE)
                        throw new ArithmeticException("Stock including reserved units exceeds integer capacity");
                }
                update(c, "UPDATE products SET stock = ?, alerted = FALSE WHERE sku = ?", stock, sku);
                alert(c, sku, stock);
                return null;
            });
            deliverAfterCommit();
            return null;
        });
    }

    @Override public Reservation reserve(String orderId, String sku, int quantity) {
        return reserve(orderId, orderId, sku, quantity);
    }

    @Override public Reservation reserve(String key, String orderId, String sku, int quantity) {
        return measured(() -> {
            text(key, "idempotencyKey"); text(orderId, "orderId"); text(sku, "sku"); positive(quantity);
            // Separate committed admission transaction: even rejected business requests consume quota.
            if (options.requestsPerWindow() > 0) transaction(c -> { admit(c); return null; });
            Reservation result = transaction(c -> {
                expire(c);
                try (var s = statement(c, "SELECT order_id FROM idempotency_keys WHERE request_key = ?", key);
                     var r = s.executeQuery()) {
                    if (r.next() && !orderId.equals(r.getString(1)))
                        throw new IllegalArgumentException("Idempotency key reused with different request");
                }
                Stored existing = reservation(c, orderId);
                if (existing != null) {
                    Reservation old = existing.value();
                    if (!old.sku().equals(sku) || old.quantity() != quantity)
                        throw new IllegalArgumentException("Order reused with different request");
                    bindKey(c, key, orderId);
                    return old;
                }
                Product p = product(c, sku);
                if (p == null) throw new InsufficientStockException(sku, quantity, 0);
                CategoryPolicy policy = options.policies().get(p.category());
                if (quantity > policy.orderLimit())
                    throw new OrderLimitExceededException(sku, quantity, policy.orderLimit());
                if (p.stock() < quantity) throw new InsufficientStockException(sku, quantity, p.stock());
                Reservation value = new Reservation(orderId, sku, quantity, clock.instant().plus(policy.paymentWindow()));
                update(c, "UPDATE products SET stock = stock - ? WHERE sku = ?", quantity, sku);
                update(c, "INSERT INTO reservations VALUES (?, ?, ?, ?, 'ACTIVE')", orderId, sku, quantity, value.expiresAt());
                bindKey(c, key, orderId);
                alert(c, sku, p.stock() - quantity);
                return value;
            });
            deliverAfterCommit();
            return result;
        });
    }

    private void bindKey(Connection c, String key, String order) throws SQLException {
        try (var s = statement(c, "SELECT order_id FROM idempotency_keys WHERE request_key = ?", key); var r = s.executeQuery()) {
            if (!r.next()) update(c, "INSERT INTO idempotency_keys VALUES (?, ?)", key, order);
        }
    }

    @Override public void confirm(String orderId) {
        measured(() -> {
            text(orderId, "orderId");
            transaction(c -> {
                expire(c);
                Stored r = reservation(c, orderId);
                if (r == null || "EXPIRED".equals(r.status())) throw new IllegalStateException("No active reservation: " + orderId);
                update(c, "UPDATE reservations SET status = 'CONFIRMED' WHERE order_id = ?", orderId);
                return null;
            });
            return null;
        });
    }

    @Override public int available(String sku) {
        return measured(() -> {
            text(sku, "sku");
            return transaction(c -> { expire(c); Product p = product(c, sku); return p == null ? 0 : p.stock(); });
        });
    }

    @Override public void expireReservations() {
        measured(() -> transaction(c -> { expire(c); return null; }));
    }

    private void expire(Connection c) throws SQLException {
        try (var s = statement(c, "SELECT order_id, sku, quantity FROM reservations WHERE status = 'ACTIVE' AND expires_at <= ?", clock.instant());
             var r = s.executeQuery()) {
            while (r.next()) {
                update(c, "UPDATE products SET stock = stock + ? WHERE sku = ?", r.getInt(3), r.getString(2));
                update(c, "UPDATE reservations SET status = 'EXPIRED' WHERE order_id = ?", r.getString(1));
            }
        }
    }

    private void alert(Connection c, String sku, int available) throws SQLException {
        Product p = product(c, sku);
        if (available <= 5 && !p.alerted()) {
            update(c, "UPDATE products SET alerted = TRUE WHERE sku = ?", sku);
            update(c, "INSERT INTO alert_outbox VALUES (?, ?, ?, NULL, NULL)", UUID.randomUUID().toString(), sku, available);
        }
    }

    /** Durable outbox. Call periodically to retry failed deliveries; callbacks run outside inventory locks. */
    @Override public void dispatchAlerts() {
        // A bounded batch avoids a slow listener making a request run indefinitely.
        for (int i = 0; i < 100; i++) {
            String token = UUID.randomUUID().toString();
            Alert event = transaction(c -> {
                try (var s = statement(c, "SELECT id, sku, available FROM alert_outbox WHERE lease_until IS NULL OR lease_until <= ? ORDER BY id FETCH FIRST 1 ROW ONLY", clock.instant());
                     var r = s.executeQuery()) {
                    if (!r.next()) return null;
                    Alert a = new Alert(r.getString(1), r.getString(2), r.getInt(3), clock.instant().plusSeconds(60));
                    update(c, "UPDATE alert_outbox SET lease_until = ?, claim_token = ? WHERE id = ?", a.lease(), token, a.id());
                    return a;
                }
            });
            if (event == null) return;
            try {
                listener.onLowStock(event.sku(), event.available());
            } catch (RuntimeException e) {
                metrics.alertFailure();
                transaction(c -> { update(c, "UPDATE alert_outbox SET lease_until = NULL, claim_token = NULL WHERE id = ? AND claim_token = ?", event.id(), token); return null; });
                return;
            }
            transaction(c -> { update(c, "DELETE FROM alert_outbox WHERE id = ? AND claim_token = ?", event.id(), token); return null; });
        }
    }

    private void deliverAfterCommit() {
        try { dispatchAlerts(); }
        catch (RuntimeException deliveryFailure) { metrics.alertFailure(); }
    }

    private void admit(Connection c) throws SQLException {
        Instant now = clock.instant();
        try (var s = statement(c, "SELECT window_start, used FROM rate_bucket WHERE id = 1"); var r = s.executeQuery()) {
            if (!r.next()) {
                update(c, "INSERT INTO rate_bucket VALUES (1, ?, 1)", now);
                return;
            }
            Instant start = r.getObject(1, OffsetDateTime.class).toInstant();
            Instant end = start.plus(options.rateWindow());
            if (!now.isBefore(end)) update(c, "UPDATE rate_bucket SET window_start = ?, used = 1 WHERE id = 1", now);
            else if (r.getInt(2) >= options.requestsPerWindow()) throw new RateLimitExceededException(Duration.between(now, end));
            else update(c, "UPDATE rate_bucket SET used = used + 1 WHERE id = 1");
        }
    }

    private Product product(Connection c, String sku) throws SQLException {
        try (var s = statement(c, "SELECT category, stock, alerted FROM products WHERE sku = ?", sku); var r = s.executeQuery()) {
            return r.next() ? new Product(ProductCategory.valueOf(r.getString(1)), r.getInt(2), r.getBoolean(3)) : null;
        }
    }

    private Stored reservation(Connection c, String orderId) throws SQLException {
        try (var s = statement(c, "SELECT sku, quantity, expires_at, status FROM reservations WHERE order_id = ?", orderId); var r = s.executeQuery()) {
            return r.next() ? new Stored(new Reservation(orderId, r.getString(1), r.getInt(2),
                    r.getObject(3, OffsetDateTime.class).toInstant()), r.getString(4)) : null;
        }
    }

    private <T> T transaction(SqlWork<T> work) {
        if (closed) throw new IllegalStateException("Inventory service closed");
        for (int attempt = 1; ; attempt++) {
            try (Connection c = DriverManager.getConnection(options.jdbcUrl())) {
                c.setAutoCommit(false);
                c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                try {
                    try (var s = c.createStatement(); var r = s.executeQuery("SELECT id FROM inventory_lock WHERE id = 1 FOR UPDATE")) {
                        if (!r.next()) throw new SQLException("Inventory lock missing");
                    }
                    T value = work.run(c);
                    c.commit();
                    return value;
                } catch (SQLException | RuntimeException e) {
                    try { c.rollback(); } catch (SQLException rollback) { e.addSuppressed(rollback); throw new IllegalStateException("Rollback failed; not safe to retry", e); }
                    throw e;
                }
            } catch (SQLException e) {
                boolean retryable = "40001".equals(e.getSQLState()) || "HYT00".equals(e.getSQLState()) || e.getErrorCode() == 50200;
                if (!retryable || attempt >= options.maxAttempts()) throw new IllegalStateException("Inventory transaction failed", e);
                metrics.retry();
                long cap = Math.min(1000, options.retryBaseDelay().toMillis() * (1L << (attempt - 1)));
                try { Thread.sleep(cap == 0 ? 0 : ThreadLocalRandom.current().nextLong(cap + 1)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("Retry interrupted", interrupted); }
            }
        }
    }

    private <T> T measured(Supplier<T> call) {
        long start = System.nanoTime(); boolean failed = true;
        try { T result = call.get(); failed = false; return result; }
        finally { metrics.record(System.nanoTime() - start, failed); }
    }

    private static PreparedStatement statement(Connection c, String sql, Object... values) throws SQLException {
        PreparedStatement s = c.prepareStatement(sql);
        for (int i = 0; i < values.length; i++) {
            Object value = values[i];
            s.setObject(i + 1, value instanceof Instant instant ? instant.atOffset(ZoneOffset.UTC) : value);
        }
        return s;
    }
    private static void update(Connection c, String sql, Object... values) throws SQLException {
        try (var s = statement(c, sql, values)) { s.executeUpdate(); }
    }
    private static void text(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 200) throw new IllegalArgumentException(name + " must contain 1..200 characters");
    }
    private static void positive(int quantity) {
        if (quantity <= 0) throw new IllegalArgumentException("Quantity must be positive");
    }
    @Override public LatencyMetrics.Snapshot metrics() { return metrics.snapshot(); }
    @Override public void close() {
        closed = true;
        try { keeper.close(); } catch (SQLException e) { throw new IllegalStateException("Cannot close database", e); }
    }
    @FunctionalInterface private interface SqlWork<T> { T run(Connection c) throws SQLException; }
    private record Product(ProductCategory category, int stock, boolean alerted) { }
    private record Stored(Reservation value, String status) { }
    private record Alert(String id, String sku, int available, Instant lease) { }
}
