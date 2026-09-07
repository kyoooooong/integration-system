package com.integration.stay.supplier.normalize;

/** 거부까지는 아니지만 기록해야 할 사실. */
public record Diagnostic(DiagnosticKind kind, String field, String expected, String actual) {}
