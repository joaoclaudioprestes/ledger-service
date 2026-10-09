package com.prestes.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LedgerTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestRestTemplate http;
    @Autowired JdbcClient db;

    @SuppressWarnings("unchecked")
    UUID newAccount(String name) {
        var r = http.postForEntity("/accounts", Map.of("name", name), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) r.getBody().get("id"));
    }

    void deposit(UUID id, long cents) {
        var r = http.postForEntity("/accounts/" + id + "/deposits", Map.of("amountCents", cents), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    void transfer(UUID from, UUID to, long cents) {
        var r = http.postForEntity("/transfers", Map.of("from", from, "to", to, "amountCents", cents), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @SuppressWarnings("unchecked")
    long balance(UUID id) {
        return ((Number) http.getForObject("/accounts/" + id + "/balance", Map.class).get("balanceCents")).longValue();
    }

    @Test
    void balanceMatchesSumOfEntriesAndLedgerNetsToZero() {
        var a = newAccount("a");
        var b = newAccount("b");
        deposit(a, 1000);
        transfer(a, b, 1000);
        transfer(b, a, 250);
        transfer(a, b, 5);

        assertThat(balance(a)).isEqualTo(245);
        assertThat(balance(b)).isEqualTo(755);

        long sumA = db.sql("SELECT SUM(CASE direction WHEN 'CREDIT' THEN amount_cents ELSE -amount_cents END) FROM entries WHERE account_id = ?")
                .param(a).query(Long.class).single();
        assertThat(balance(a)).isEqualTo(sumA);
        long total = db.sql("SELECT SUM(CASE direction WHEN 'CREDIT' THEN amount_cents ELSE -amount_cents END) FROM entries")
                .query(Long.class).single();
        assertThat(total).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void statementIsPaginated() {
        var a = newAccount("a");
        var b = newAccount("b");
        deposit(a, 5);
        for (int i = 0; i < 5; i++) transfer(a, b, 1);

        var p = http.getForObject("/accounts/" + a + "/entries?page=1&size=2", Map.class);
        assertThat((java.util.List<?>) p.get("items")).hasSize(2);
        assertThat(((Number) p.get("total")).longValue()).isEqualTo(6);
    }

    @Test
    void rejectsOverdraft() {
        var a = newAccount("a");
        deposit(a, 10);
        var r = http.postForEntity("/transfers", Map.of("from", a, "to", newAccount("b"), "amountCents", 11), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(balance(a)).isEqualTo(10);
    }

    @Test
    void thousandConcurrentTransfersCloseExactBalance() throws Exception {
        var a = newAccount("a");
        var b = newAccount("b");
        deposit(a, 500);
        deposit(b, 500);
        // 1000 transfers, a->b of 1 and b->a of 2 alternating, plus overdraft attempts must never go negative
        var pool = java.util.concurrent.Executors.newFixedThreadPool(50);
        var ok = new java.util.concurrent.atomic.AtomicInteger();
        var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
        for (int i = 0; i < 1000; i++) {
            boolean ab = i % 2 == 0;
            futures.add(pool.submit(() -> {
                var r = http.postForEntity("/transfers", ab ? Map.of("from", a, "to", b, "amountCents", 1)
                        : Map.of("from", b, "to", a, "amountCents", 2), Map.class);
                assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
                ok.incrementAndGet();
            }));
        }
        for (var f : futures) f.get();
        pool.shutdown();

        assertThat(ok.get()).isEqualTo(1000);
        // a: 500 - 500*1 + 500*2 = 1000 ; b: 500 + 500 - 1000 = 0
        assertThat(balance(a)).isEqualTo(1000);
        assertThat(balance(b)).isZero();
        long total = db.sql("SELECT SUM(balance_cents) FROM accounts").query(Long.class).single();
        assertThat(total).isZero(); // incl. SYSTEM (-1000): nothing created or lost
    }

    @Test
    void concurrentOverdraftNeverGoesNegative() throws Exception {
        var a = newAccount("a");
        var b = newAccount("b");
        deposit(a, 100);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(50);
        var futures = new java.util.ArrayList<java.util.concurrent.Future<HttpStatus>>();
        for (int i = 0; i < 200; i++) {
            futures.add(pool.submit(() -> HttpStatus.valueOf(http.postForEntity("/transfers",
                    Map.of("from", a, "to", b, "amountCents", 1), Map.class).getStatusCode().value())));
        }
        long created = 0;
        for (var f : futures) if (f.get() == HttpStatus.CREATED) created++;
        pool.shutdown();

        assertThat(created).isEqualTo(100);
        assertThat(balance(a)).isZero();
        assertThat(balance(b)).isEqualTo(100);
    }

    @Test
    void rejectsInvalidTransfers() {
        var a = newAccount("a");
        var r = http.postForEntity("/transfers", Map.of("from", a, "to", a, "amountCents", 1), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        r = http.postForEntity("/transfers", Map.of("from", a, "to", UUID.randomUUID(), "amountCents", 1), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        r = http.postForEntity("/transfers", Map.of("from", a, "to", newAccount("b"), "amountCents", 0), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.getForEntity("/accounts/" + UUID.randomUUID() + "/balance", Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
