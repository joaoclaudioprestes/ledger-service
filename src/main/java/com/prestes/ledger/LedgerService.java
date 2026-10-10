package com.prestes.ledger;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LedgerService {

    public record Account(UUID id, String name) {}
    public record Entry(UUID id, UUID transactionId, UUID accountId, String direction, long amountCents,
                        java.time.Instant createdAt) {}
    public record Page(List<Entry> items, int page, int size, long total) {}

    public static class NotFound extends RuntimeException {
        NotFound(String m) { super(m); }
    }

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LedgerService.class);

    private final JdbcClient db;
    private final io.micrometer.core.instrument.MeterRegistry meters;

    public LedgerService(JdbcClient db, io.micrometer.core.instrument.MeterRegistry meters) {
        this.db = db;
        this.meters = meters;
    }

    @Transactional
    public Account createAccount(String name) {
        var id = UUID.randomUUID();
        db.sql("INSERT INTO accounts (id, name) VALUES (?, ?)").params(id, name).update();
        return new Account(id, name);
    }

    public static final UUID SYSTEM = UUID.fromString("00000000-0000-0000-0000-000000000001");

    public static class InsufficientFunds extends RuntimeException {
        InsufficientFunds(String m) { super(m); }
    }

    public long balance(UUID accountId) {
        return db.sql("SELECT balance_cents FROM accounts WHERE id = ?").param(accountId).query(Long.class)
                .optional().orElseThrow(() -> new NotFound("account " + accountId + " not found"));
    }

    public static class KeyConflict extends RuntimeException {
        KeyConflict(String m) { super(m); }
    }

    /**
     * Same key + same payload returns the original transaction id without transferring again; same key with a
     * different payload throws KeyConflict. The key row is inserted in the transfer's own transaction, so a failed
     * transfer leaves no key behind, and concurrent duplicates block on the unique index until the first commits.
     */
    @Transactional
    public UUID transfer(String key, UUID from, UUID to, long amountCents) {
        rejectSystem(from, to);
        db.sql("DELETE FROM idempotency_keys WHERE key = ? AND expires_at < now()").param(key).update();
        var hash = from + ":" + to + ":" + amountCents;
        var tx = UUID.randomUUID();
        int inserted = db.sql("""
                INSERT INTO idempotency_keys (key, request_hash, transaction_id, expires_at)
                VALUES (?, ?, ?, now() + make_interval(hours => ?)) ON CONFLICT (key) DO NOTHING""")
                .params(key, hash, tx, KEY_TTL_HOURS).update();
        if (inserted == 0) {
            var row = db.sql("SELECT request_hash, transaction_id FROM idempotency_keys WHERE key = ?").param(key)
                    .query((rs, n) -> new Object[]{rs.getString(1), rs.getObject(2, UUID.class)}).single();
            if (!row[0].equals(hash)) throw new KeyConflict("Idempotency-Key reused with a different payload");
            return (UUID) row[1];
        }
        try {
            var id = transfer(tx, from, to, amountCents);
            meters.counter("ledger.transfers", "outcome", "ok").increment();
            log.info("transfer {} from={} to={} amountCents={}", id, from, to, amountCents);
            return id;
        } catch (RuntimeException e) {
            meters.counter("ledger.transfers", "outcome", e.getClass().getSimpleName()).increment();
            throw e;
        }
    }

    static final int KEY_TTL_HOURS = 24;

    @Scheduled(fixedDelay = 3600_000)
    public void purgeExpiredKeys() {
        db.sql("DELETE FROM idempotency_keys WHERE expires_at < now()").update();
    }

    /** Funds an account from the SYSTEM account. */
    @Transactional
    public UUID deposit(UUID account, long amountCents) {
        rejectSystem(account);
        return transfer(UUID.randomUUID(), SYSTEM, account, amountCents);
    }

    public Page statement(UUID accountId, int page, int size) {
        requireAccount(accountId);
        long total = db.sql("SELECT count(*) FROM entries WHERE account_id = ?").param(accountId)
                .query(Long.class).single();
        var items = db.sql("""
                SELECT id, transaction_id, account_id, direction, amount_cents, created_at FROM entries
                WHERE account_id = ? ORDER BY created_at DESC, id LIMIT ? OFFSET ?""")
                .params(accountId, size, (long) page * size)
                .query((rs, n) -> new Entry(rs.getObject("id", UUID.class), rs.getObject("transaction_id", UUID.class),
                        rs.getObject("account_id", UUID.class), rs.getString("direction"),
                        rs.getLong("amount_cents"), rs.getTimestamp("created_at").toInstant()))
                .list();
        return new Page(items, page, size, total);
    }

    /**
     * Double entry: one debit on `from`, one credit on `to`, same transaction. Returns transaction id.
     * Pessimistic locking: both rows are locked in id order (no deadlocks), so concurrent transfers on hot
     * accounts serialize instead of conflicting and retrying (optimistic would starve under heavy contention).
     */
    @Transactional
    public UUID transfer(UUID from, UUID to, long amountCents) {
        rejectSystem(from, to);
        return transfer(UUID.randomUUID(), from, to, amountCents);
    }

    private UUID transfer(UUID tx, UUID from, UUID to, long amountCents) {
        var rows = db.sql("SELECT id, balance_cents, allow_negative FROM accounts WHERE id IN (?, ?) ORDER BY id FOR UPDATE")
                .params(from, to)
                .query((rs, n) -> new Object[]{rs.getObject("id", UUID.class), rs.getLong("balance_cents"),
                        rs.getBoolean("allow_negative")})
                .list();
        if (rows.size() < 2) {
            for (var id : new UUID[]{from, to}) {
                if (rows.stream().noneMatch(r -> r[0].equals(id))) throw new NotFound("account " + id + " not found");
            }
        }
        var src = rows.stream().filter(r -> r[0].equals(from)).findFirst().orElseThrow();
        if (!(boolean) src[2] && (long) src[1] < amountCents) {
            throw new InsufficientFunds("account " + from + " has insufficient funds");
        }
        db.sql("UPDATE accounts SET balance_cents = balance_cents - ? WHERE id = ?").params(amountCents, from).update();
        db.sql("UPDATE accounts SET balance_cents = balance_cents + ? WHERE id = ?").params(amountCents, to).update();
        insert(tx, from, "DEBIT", amountCents);
        insert(tx, to, "CREDIT", amountCents);
        return tx;
    }

    private void insert(UUID tx, UUID account, String direction, long amount) {
        db.sql("INSERT INTO entries (id, transaction_id, account_id, direction, amount_cents) VALUES (?, ?, ?, ?, ?)")
                .params(UUID.randomUUID(), tx, account, direction, amount).update();
    }

    /** SYSTEM may only be touched by deposit(); it allows negative balance, so any other use would mint money. */
    private static void rejectSystem(UUID... accounts) {
        for (var a : accounts) {
            if (SYSTEM.equals(a)) throw new IllegalArgumentException("SYSTEM account cannot be used directly");
        }
    }

    private void requireAccount(UUID id) {
        boolean ok = db.sql("SELECT count(*) FROM accounts WHERE id = ?").param(id).query(Long.class).single() > 0;
        if (!ok) throw new NotFound("account " + id + " not found");
    }
}
