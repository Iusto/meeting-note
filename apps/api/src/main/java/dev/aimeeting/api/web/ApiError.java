package dev.aimeeting.api.web;

public record ApiError(String code, String message, String requestId) {}
