package dev.aimeeting.api.topic;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/topics")
public class TopicController {
    private final TopicService topics;

    public TopicController(TopicService topics) { this.topics = topics; }

    @GetMapping public List<Topic> list() { return topics.list(); }
    @PostMapping public Topic create(@Valid @RequestBody CreateTopic request) {
        return topics.create(request.name(), request.description());
    }
    @GetMapping("/{topicId}") public Topic find(@PathVariable UUID topicId) { return topics.find(topicId); }
    @PatchMapping("/{topicId}") public Topic update(@PathVariable UUID topicId, @RequestBody UpdateTopic request) {
        return topics.update(topicId, request.name(), request.description());
    }
    @DeleteMapping("/{topicId}") public ResponseEntity<Void> delete(@PathVariable UUID topicId) {
        topics.delete(topicId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{topicId}/terms") public List<GlossaryTerm> terms(@PathVariable UUID topicId) {
        return topics.terms(topicId);
    }
    @PostMapping("/{topicId}/terms") public GlossaryTerm createTerm(@PathVariable UUID topicId,
                                                                     @RequestBody TermRequest request) {
        return topics.createTerm(topicId, request.canonical(), request.meaning(), request.aliases(), request.status(), "MANUAL");
    }
    @PatchMapping("/{topicId}/terms/{termId}") public GlossaryTerm updateTerm(
            @PathVariable UUID topicId, @PathVariable UUID termId, @RequestBody TermRequest request) {
        return topics.updateTerm(topicId, termId, request.canonical(), request.meaning(), request.aliases(), request.status());
    }
    @DeleteMapping("/{topicId}/terms/{termId}") public ResponseEntity<Void> deleteTerm(
            @PathVariable UUID topicId, @PathVariable UUID termId) {
        topics.deleteTerm(topicId, termId);
        return ResponseEntity.noContent().build();
    }
    @GetMapping("/{topicId}/keyterms") public List<String> keyterms(@PathVariable UUID topicId) {
        return topics.keyterms(topicId);
    }
    @PostMapping("/{topicId}/suggest-terms") public List<GlossaryTerm> suggest(@PathVariable UUID topicId) {
        return topics.suggest(topicId);
    }

    public record CreateTopic(@NotBlank @Size(max = 200) String name, @Size(max = 2_000) String description) {}
    public record UpdateTopic(String name, String description) {}
    public record TermRequest(String canonical, String meaning, List<String> aliases, String status) {}
}
