package dev.aimeeting.api.topic;

import java.util.UUID;

public record Topic(UUID id, String name, String description) {}
