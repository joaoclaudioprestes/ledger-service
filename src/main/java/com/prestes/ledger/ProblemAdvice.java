package com.prestes.ledger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.sql.SQLException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Framework errors (bad JSON, missing header, 404...) become RFC 9457 via the base class; anything else is a 500 problem. */
@RestControllerAdvice
class ProblemAdvice extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ProblemAdvice.class);

    /** SQLState 22003 = numeric value out of range: accumulated balance would exceed BIGINT. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail integrity(DataIntegrityViolationException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && "22003".equals(sql.getSQLState())) {
                return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, "balance out of range");
            }
        }
        return unexpected(e);
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        log.error("unhandled error", e);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "internal error");
    }
}
