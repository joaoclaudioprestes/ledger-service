package com.prestes.ledger;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
public class LedgerController {

    public record CreateAccount(@NotBlank @Size(max = 120) String name) {}
    public record Transfer(@NotNull UUID from, @NotNull UUID to, @Positive long amountCents) {}

    public record Deposit(@Positive long amountCents) {}

    private final LedgerService service;

    public LedgerController(LedgerService service) {
        this.service = service;
    }

    @PostMapping("/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    LedgerService.Account create(@Valid @RequestBody CreateAccount req) {
        return service.createAccount(req.name());
    }

    @GetMapping("/accounts/{id}/balance")
    Map<String, Object> balance(@PathVariable UUID id) {
        return Map.of("accountId", id, "balanceCents", service.balance(id));
    }

    @GetMapping("/accounts/{id}/entries")
    LedgerService.Page statement(@PathVariable UUID id, @RequestParam(defaultValue = "0") @Min(0) int page,
                                 @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.statement(id, page, size);
    }

    @PostMapping("/transfers")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, UUID> transfer(@RequestHeader("Idempotency-Key") @NotBlank @Size(max = 255) String key,
                               @Valid @RequestBody Transfer req) {
        if (req.from().equals(req.to())) throw new IllegalArgumentException("from and to must differ");
        return Map.of("transactionId", service.transfer(key, req.from(), req.to(), req.amountCents()));
    }

    @PostMapping("/accounts/{id}/deposits")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, UUID> deposit(@PathVariable UUID id, @Valid @RequestBody Deposit req) {
        return Map.of("transactionId", service.deposit(id, req.amountCents()));
    }

    @ExceptionHandler(LedgerService.InsufficientFunds.class)
    ProblemDetail insufficient(LedgerService.InsufficientFunds e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }

    @ExceptionHandler(LedgerService.KeyConflict.class)
    ProblemDetail keyConflict(LedgerService.KeyConflict e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }

    @ExceptionHandler(LedgerService.NotFound.class)
    ProblemDetail notFound(LedgerService.NotFound e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(jakarta.validation.ConstraintViolationException.class)
    ProblemDetail invalid(jakarta.validation.ConstraintViolationException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail bad(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }
}
