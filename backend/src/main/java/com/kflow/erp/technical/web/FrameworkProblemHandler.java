package com.kflow.erp.technical.web;

import java.net.URI;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Framework request errors only; programming and future domain errors are not reclassified. */
@RestControllerAdvice
public class FrameworkProblemHandler extends ResponseEntityExceptionHandler {
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, "Request validation failed.");
        problem.setProperty("errors", ex.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of("field", error.getField(),
                        "reason", error.getDefaultMessage() == null ? "Invalid value" : error.getDefaultMessage()))
                .toList());
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> createResponseEntity(
            Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (body instanceof ProblemDetail problem) {
            String code = status.value() == 400 ? "INVALID_REQUEST"
                    : status.is4xxClientError() ? "REQUEST_REJECTED" : "REQUEST_PROCESSING_FAILED";
            problem.setProperty("code", code);
            problem.setType(URI.create("urn:kflow:problem:" + code.toLowerCase(Locale.ROOT).replace('_', '-')));
            // Keep binding values, converter diagnostics and internal exception text out of the response.
            if (status.value() == 400) {
                problem.setDetail("Request is malformed or failed validation.");
            } else if (status.is5xxServerError()) {
                problem.setDetail("The server could not process the request.");
            }
        }
        return super.createResponseEntity(body, headers, status, request);
    }
}
