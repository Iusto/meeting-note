package dev.aimeeting.api.topic;

import dev.aimeeting.api.topic.TopicRepository.AliasValue;
import dev.aimeeting.api.web.ApiException;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class TopicService {
    private final TopicRepository topics;

    public TopicService(TopicRepository topics) { this.topics = topics; }

    public List<Topic> list() { return topics.list(); }
    public Topic find(UUID id) { return topics.find(id); }

    public Topic create(String name, String description) {
        return topics.create(required(name, 200, "Topic name"), optional(description, 2_000));
    }

    public Topic update(UUID id, String name, String description) {
        Topic current = topics.find(id);
        return topics.update(id, name == null ? current.name() : required(name, 200, "Topic name"),
                description == null ? current.description() : optional(description, 2_000));
    }

    public void delete(UUID id) { topics.delete(id); }
    public List<GlossaryTerm> terms(UUID topicId) { return topics.terms(topicId); }

    public GlossaryTerm createTerm(UUID topicId, String canonical, String meaning, List<String> aliases,
                                   String status, String source) {
        String cleanCanonical = required(canonical, 200, "Canonical term");
        String cleanMeaning = required(meaning, 2_000, "Meaning");
        String validStatus = validateStatus(status == null ? "PENDING" : status);
        return topics.createTerm(topicId, cleanCanonical, normalize(cleanCanonical), cleanMeaning,
                aliases(cleanCanonical, aliases), validStatus, source);
    }

    public GlossaryTerm updateTerm(UUID topicId, UUID termId, String canonical, String meaning,
                                   List<String> aliases, String status) {
        GlossaryTerm current = topics.term(topicId, termId);
        String nextCanonical = canonical == null ? current.canonical() : required(canonical, 200, "Canonical term");
        String nextMeaning = meaning == null ? current.meaning() : required(meaning, 2_000, "Meaning");
        List<String> nextAliases = aliases == null ? current.aliases() : aliases;
        return topics.updateTerm(topicId, termId, nextCanonical, normalize(nextCanonical), nextMeaning,
                aliases(nextCanonical, nextAliases), status == null ? current.status() : validateStatus(status));
    }

    public void deleteTerm(UUID topicId, UUID termId) { topics.deleteTerm(topicId, termId); }
    public List<String> keyterms(UUID topicId) { return topics.approvedKeyterms(topicId); }

    public List<GlossaryTerm> suggest(UUID topicId) {
        topics.find(topicId);
        GlossaryTerm mes = suggestion(topicId, "MES", "Manufacturing Execution System", List.of("엠이에스"));
        GlossaryTerm lot = suggestion(topicId, "LOT", "생산 및 추적 단위", List.of("로떠", "로뜨"));
        return List.of(mes, lot);
    }

    private GlossaryTerm suggestion(UUID topicId, String canonical, String meaning, List<String> aliases) {
        GlossaryTerm existing = topics.findByNormalizedCanonical(topicId, normalize(canonical));
        if (existing != null) return existing;
        return createTerm(topicId, canonical, meaning, aliases, "PENDING", "MOCK");
    }

    private List<AliasValue> aliases(String canonical, List<String> values) {
        if (values == null) return List.of();
        if (values.size() > 20) invalid("A term may contain at most 20 aliases");
        String normalizedCanonical = normalize(canonical);
        HashSet<String> seen = new HashSet<>();
        return values.stream().map(value -> {
            String clean = required(value, 200, "Alias");
            String normalized = normalize(clean);
            if (normalized.equals(normalizedCanonical) || !seen.add(normalized)) invalid("Aliases must be normalized-unique and differ from the canonical term");
            return new AliasValue(clean, normalized);
        }).toList();
    }

    static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private String validateStatus(String status) {
        if (!List.of("PENDING", "APPROVED", "REJECTED").contains(status)) invalid("Glossary status is invalid");
        return status;
    }

    private String required(String value, int max, String label) {
        if (value == null || value.trim().isEmpty() || value.trim().length() > max) invalid(label + " is invalid");
        return value.trim();
    }

    private String optional(String value, int max) {
        if (value == null) return null;
        if (value.length() > max) invalid("Text is too long");
        return value.trim();
    }

    private void invalid(String message) { throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message); }
}
