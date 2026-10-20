package dev.aimeeting.api.topic;

import java.util.List;
import java.util.UUID;

public record GlossaryTerm(UUID id, String canonical, String meaning, List<String> aliases,
                           String status, String source) {}
