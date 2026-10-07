package com.rtgs.common;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler({IllegalArgumentException.class, ArithmeticException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> invalid(RuntimeException exception) {
        return Map.of("error", exception.getMessage() == null ? "invalid request" : exception.getMessage());
    }
}
