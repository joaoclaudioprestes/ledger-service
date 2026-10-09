package com.prestes.ledger;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
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

    private final JdbcClient db;

    public LedgerService(JdbcClient db) {
        this.db = db;
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

    /** Funds an account from the SYSTEM account. */
    @Transactional
    public UUID deposit(UUID account, long amountCents) {
        return transfer(SYSTEM, account, amountCents);
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
        var tx = UUID.randomUUID();
        insert(tx, from, "DEBIT", amountCents);
        insert(tx, to, "CREDIT", amountCents);
        return tx;
    }

    private void insert(UUID tx, UUID account, String direction, long amount) {
        db.sql("INSERT INTO entries (id, transaction_id, account_id, direction, amount_cents) VALUES (?, ?, ?, ?, ?)")
                .params(UUID.randomUUID(), tx, account, direction, amount).update();
    }

    private void requireAccount(UUID id) {
        boolean ok = db.sql("SELECT count(*) FROM accounts WHERE id = ?").param(id).query(Long.class).single() > 0;
        if (!ok) throw new NotFound("account " + id + " not found");
    }
}
