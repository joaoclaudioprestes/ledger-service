package com.prestes.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ObservabilityTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestRestTemplate http;

    @SuppressWarnings("unchecked")
    UUID account() {
        return UUID.fromString((String) http.postForEntity("/accounts", Map.of("name", "x"), Map.class).getBody().get("id"));
    }

    @Test
    void everyErrorIsProblemJson() {
        var json = HttpMethod.POST;
        var missingKey = http.exchange("/transfers", json, new HttpEntity<>(Map.of("a", 1)), Map.class);
        var notFound = http.getForEntity("/accounts/" + UUID.randomUUID() + "/balance", Map.class);
        var noRoute = http.getForEntity("/nope", Map.class);
        var badJson = http.exchange("/accounts", json, new HttpEntity<>("{", jsonHeaders()), Map.class);
        var badUuid = http.getForEntity("/accounts/xyz/balance", Map.class);
        for (var r : new org.springframework.http.ResponseEntity[]{missingKey, notFound, noRoute, badJson, badUuid}) {
            assertThat(r.getStatusCode().isError()).isTrue();
            assertThat(r.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        }
    }

    @Test
    void transferTraceIdIsLoggedAndMetricsExposed(CapturedOutput out) {
        var a = account();
        var b = account();
        http.postForEntity("/accounts/" + a + "/deposits", Map.of("amountCents", 100), Map.class);
        var h = jsonHeaders();
        h.set("Idempotency-Key", UUID.randomUUID().toString());
        http.exchange("/transfers", HttpMethod.POST,
                new HttpEntity<>(Map.of("from", a, "to", b, "amountCents", 10), h), Map.class);
        assertThat(out.getAll()).containsPattern("\"message\":\"transfer [^\n]*\"traceId\":\"[0-9a-f]{32}\"");
        var prom = http.getForObject("/actuator/prometheus", String.class);
        assertThat(prom).contains("ledger_transfers_total{").contains("http_server_requests_seconds_count");
    }

    @Test
    void loadWithVirtualThreads() throws Exception {
        var a = account();
        var b = account();
        http.postForEntity("/accounts/" + a + "/deposits", Map.of("amountCents", 1000), Map.class);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = java.util.stream.IntStream.range(0, 500).mapToObj(i -> pool.submit(() -> {
                var h = jsonHeaders();
                h.set("Idempotency-Key", UUID.randomUUID().toString());
                return http.exchange("/transfers", HttpMethod.POST,
                        new HttpEntity<>(Map.of("from", a, "to", b, "amountCents", 1), h), Map.class).getStatusCode().value();
            })).toList();
            for (var f : futures) assertThat(f.get()).isEqualTo(201);
        }
        assertThat(((Number) http.getForObject("/accounts/" + b + "/balance", Map.class).get("balanceCents")).longValue())
                .isEqualTo(500);
    }

    static HttpHeaders jsonHeaders() {
        var h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }
}
