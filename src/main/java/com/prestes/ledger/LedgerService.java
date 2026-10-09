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

    /** Balance = credits - debits. */
    public long balance(UUID accountId) {
        requireAccount(accountId);
        return db.sql("""
                SELECT COALESCE(SUM(CASE direction WHEN 'CREDIT' THEN amount_cents ELSE -amount_cents END), 0)
                FROM entries WHERE account_id = ?""").param(accountId).query(Long.class).single();
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

    /** Double entry: one debit on `from`, one credit on `to`, same transaction. Returns transaction id. */
    @Transactional
    public UUID transfer(UUID from, UUID to, long amountCents) {
        requireAccount(from);
        requireAccount(to);
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
