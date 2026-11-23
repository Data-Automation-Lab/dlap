/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.dlap.autocompliance.service.exception;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import lombok.extern.slf4j.Slf4j;

/**
 * Global exception handler for the autocompliance service. Maps domain
 * exceptions to consistent HTTP error responses so controllers do not have to
 * translate raw exceptions themselves.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(ControlEvaluationNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleControlEvaluationNotFound(ControlEvaluationNotFoundException ex) {
        log.warn("Control evaluation not found: {}", ex.getMessage());
        return buildError(HttpStatus.NOT_FOUND, "CONTROL_EVALUATION_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(ControlNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleControlNotFound(ControlNotFoundException ex) {
        log.warn("Compliance control not found: {}", ex.getMessage());
        return buildError(HttpStatus.NOT_FOUND, "CONTROL_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(ReportDefinitionNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleReportDefinitionNotFound(ReportDefinitionNotFoundException ex) {
        log.warn("Report definition not found: {}", ex.getMessage());
        return buildError(HttpStatus.NOT_FOUND, "REPORT_DEFINITION_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(ReportJobNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleReportJobNotFound(ReportJobNotFoundException ex) {
        log.warn("Report job not found: {}", ex.getMessage());
        return buildError(HttpStatus.NOT_FOUND, "REPORT_JOB_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(GeneratedReportNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleGeneratedReportNotFound(GeneratedReportNotFoundException ex) {
        log.warn("Generated report not found: {}", ex.getMessage());
        return buildError(HttpStatus.NOT_FOUND, "GENERATED_REPORT_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Illegal argument: {}", ex.getMessage());
        return buildError(HttpStatus.BAD_REQUEST, "BAD_REQUEST", ex.getMessage());
    }

    private ResponseEntity<Map<String, Object>> buildError(HttpStatus status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("code", code);
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
