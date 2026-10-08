package io.dataease.enterprise.management.server;

import io.dataease.result.ResultCode;
import io.dataease.result.ResultMessage;
import jakarta.persistence.PersistenceException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** Internal SQL and transaction diagnostics must not be serialized to management clients. */
@RestControllerAdvice(basePackages="io.dataease.enterprise.management.server")
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(name="enterprise.management.enabled",havingValue="true")
public class ManagementExceptionHandler {
    @ExceptionHandler({PersistenceException.class,DataAccessException.class,IllegalStateException.class})
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ResultMessage internalFailure() {
        return ResultMessage.failure(ResultCode.SYSTEM_INNER_ERROR);
    }
}
