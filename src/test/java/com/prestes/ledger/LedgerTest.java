package com.prestes.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

    @org.junit.jupiter.api.BeforeEach
    void defaultKey() {
        var interceptors = http.getRestTemplate().getInterceptors();
        if (!interceptors.isEmpty()) return;
        interceptors.add((req, body, ex) -> {
            if (req.getURI().getPath().equals("/transfers") && !req.getHeaders().containsKey("Idempotency-Key")
                    && req.getHeaders().getFirst("X-No-Key") == null) {
                req.getHeaders().set("Idempotency-Key", UUID.randomUUID().toString());
            }
            return ex.execute(req, body);
        });
    }

    ResponseEntity<Map> keyed(String key, Object body) {
        var h = new HttpHeaders();
        if (key != null) h.set("Idempotency-Key", key); else h.set("X-No-Key", "1");
        return http.exchange("/transfers", HttpMethod.POST, new HttpEntity<>(body, h), Map.class);
    }

    @Test
    void sameKeyTransfersOnce() {
        var a = newAccount("a");
        var b = newAccount("b");
        deposit(a, 1000);
        var body = Map.of("from", a, "to", b, "amountCents", 300);
        var k = UUID.randomUUID().toString();
        var r1 = keyed(k, body);
        var r2 = keyed(k, body);
        assertThat(r1.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r2.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r2.getBody()).isEqualTo(r1.getBody());
        assertThat(balance(a)).isEqualTo(700);
        assertThat(balance(b)).isEqualTo(300);
    }

    @Test
    void sameKeyDifferentPayloadIs422AndMissingKeyIs400() {
        var a = newAccount("a");
        var b = newAccount("b");
        deposit(a, 1000);
        var k = UUID.randomUUID().toString();
        assertThat(keyed(k, Map.of("from", a, "to", b, "amountCents", 100)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(keyed(k, Map.of("from", a, "to", b, "amountCents", 200)).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(keyed(null, Map.of("from", a, "to", b, "amountCents", 100)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(balance(a)).isEqualTo(900);
    }

    @Test
    void expiredKeyIsExecutedAgain() {
        var a = newAccount("a");
        var b = newAccount("b");
        deposit(a, 1000);
        var body = Map.of("from", a, "to", b, "amountCents", 100);
        var k = UUID.randomUUID().toString();
        keyed(k, body);
        db.sql("UPDATE idempotency_keys SET expires_at = now() - interval '1 second' WHERE key = ?").param(k).update();
        assertThat(keyed(k, body).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(balance(a)).isEqualTo(800);
    }

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
        deposit(a, 1000);
        deposit(b, 1000);
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
        // a: 1000 - 500*1 + 500*2 = 1500 ; b: 1000 + 500 - 1000 = 500
        assertThat(balance(a)).isEqualTo(1500);
        assertThat(balance(b)).isEqualTo(500);
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
