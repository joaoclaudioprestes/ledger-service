package com.prestes.ledger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Framework errors (bad JSON, missing header, 404...) become RFC 9457 via the base class; anything else is a 500 problem. */
@RestControllerAdvice
class ProblemAdvice extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ProblemAdvice.class);

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        log.error("unhandled error", e);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "internal error");
    }
}
