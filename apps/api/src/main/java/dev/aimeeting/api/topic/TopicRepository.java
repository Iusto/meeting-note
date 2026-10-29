package dev.aimeeting.api.topic;

import dev.aimeeting.api.web.ApiException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class TopicRepository {
    private final JdbcTemplate jdbc;

    public TopicRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Topic> list() {
        return jdbc.query("SELECT id,name,description FROM meeting_topics ORDER BY created_at,name", this::topic);
    }

    public Topic find(UUID id) {
        List<Topic> rows = jdbc.query("SELECT id,name,description FROM meeting_topics WHERE id=?", this::topic, id);
        if (rows.isEmpty()) throw notFound("TOPIC_NOT_FOUND", "Topic was not found");
        return rows.getFirst();
    }

    public Topic lock(UUID id) {
        List<Topic> rows = jdbc.query("SELECT id,name,description FROM meeting_topics WHERE id=? FOR UPDATE",
                this::topic, id);
        if (rows.isEmpty()) throw notFound("TOPIC_NOT_FOUND", "Topic was not found");
        return rows.getFirst();
    }

    public Topic create(String name, String description) {
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO meeting_topics(id,name,description) VALUES (?,?,?)", id, name, description);
        } catch (DataIntegrityViolationException exception) {
            throw conflict("TOPIC_NAME_CONFLICT", "Topic name already exists");
        }
        return find(id);
    }

    public Topic update(UUID id, String name, String description) {
        find(id);
        try {
            jdbc.update("UPDATE meeting_topics SET name=?,description=? WHERE id=?", name, description, id);
        } catch (DataIntegrityViolationException exception) {
            throw conflict("TOPIC_NAME_CONFLICT", "Topic name already exists");
        }
        return find(id);
    }

    @Transactional
    public void delete(UUID id) {
        find(id);
        List<UUID> termIds = jdbc.query("SELECT term_id FROM topic_terms WHERE topic_id=?",
                (rs, row) -> rs.getObject(1, UUID.class), id);
        try {
            jdbc.update("DELETE FROM meeting_topics WHERE id=?", id);
        } catch (DataIntegrityViolationException exception) {
            throw conflict("TOPIC_IN_USE", "Topic is used by a meeting");
        }
        for (UUID termId : termIds) {
            Integer links = jdbc.queryForObject("SELECT COUNT(*) FROM topic_terms WHERE term_id=?", Integer.class, termId);
            if (links != null && links == 0) jdbc.update("DELETE FROM glossary_terms WHERE id=?", termId);
        }
    }

    public List<GlossaryTerm> terms(UUID topicId) {
        find(topicId);
        return jdbc.query(TERM_SELECT + " WHERE tt.topic_id=? ORDER BY tt.priority DESC,gt.canonical", this::term, topicId);
    }

    public GlossaryTerm term(UUID topicId, UUID termId) {
        List<GlossaryTerm> rows = jdbc.query(TERM_SELECT + " WHERE tt.topic_id=? AND gt.id=?", this::term, topicId, termId);
        if (rows.isEmpty()) throw notFound("TERM_NOT_FOUND", "Glossary term was not found in this topic");
        return rows.getFirst();
    }

    public GlossaryTerm findByNormalizedCanonical(UUID topicId, String normalized) {
        List<GlossaryTerm> rows = jdbc.query(TERM_SELECT + " WHERE tt.topic_id=? AND gt.normalized_canonical=?",
                this::term, topicId, normalized);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Transactional
    public GlossaryTerm createTerm(UUID topicId, String canonical, String normalizedCanonical, String meaning,
                                   List<AliasValue> aliases, String status, String source) {
        find(topicId);
        List<UUID> globallyExisting = jdbc.query("SELECT id FROM glossary_terms WHERE normalized_canonical=?",
                (rs, row) -> rs.getObject(1, UUID.class), normalizedCanonical);
        if (!globallyExisting.isEmpty()) {
            UUID existingId = globallyExisting.getFirst();
            GlossaryTerm existing = globalTerm(existingId);
            if (!existing.meaning().equals(meaning) || !normalizedAliases(existing.aliases()).equals(
                    aliases.stream().map(AliasValue::normalized).sorted().toList())) {
                throw conflict("TERM_CONFLICT", "Canonical term already exists with different details");
            }
            jdbc.update("INSERT INTO topic_terms(topic_id,term_id,priority) VALUES (?,?,0) ON CONFLICT DO NOTHING",
                    topicId, existingId);
            return term(topicId, existingId);
        }
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO glossary_terms(id,canonical,normalized_canonical,meaning,status,source) VALUES (?,?,?,?,?,?)",
                    id, canonical, normalizedCanonical, meaning, status, source);
            for (AliasValue alias : aliases) {
                jdbc.update("INSERT INTO glossary_aliases(id,term_id,alias,normalized_alias,approved_at) VALUES (?,?,?,?,?)",
                        UUID.randomUUID(), id, alias.value(), alias.normalized(),
                        "APPROVED".equals(status) ? OffsetDateTime.now(ZoneOffset.UTC) : null);
            }
            jdbc.update("INSERT INTO topic_terms(topic_id,term_id,priority) VALUES (?,?,0)", topicId, id);
        } catch (DataIntegrityViolationException exception) {
            throw conflict("TERM_ALIAS_CONFLICT", "Canonical term or alias already exists");
        }
        return term(topicId, id);
    }

    @Transactional
    public GlossaryTerm updateTerm(UUID topicId, UUID termId, String canonical, String normalizedCanonical,
                                   String meaning, List<AliasValue> aliases, String status) {
        GlossaryTerm current = term(topicId, termId);
        try {
            jdbc.update("UPDATE glossary_terms SET canonical=?,normalized_canonical=?,meaning=?,status=? WHERE id=?",
                    canonical, normalizedCanonical, meaning, status, termId);
            jdbc.update("DELETE FROM glossary_aliases WHERE term_id=?", termId);
            for (AliasValue alias : aliases) {
                jdbc.update("INSERT INTO glossary_aliases(id,term_id,alias,normalized_alias,approved_at) VALUES (?,?,?,?,?)",
                        UUID.randomUUID(), termId, alias.value(), alias.normalized(),
                        "APPROVED".equals(status) ? OffsetDateTime.now(ZoneOffset.UTC) : null);
            }
        } catch (DataIntegrityViolationException exception) {
            throw conflict("TERM_ALIAS_CONFLICT", "Canonical term or alias already exists");
        }
        return term(topicId, current.id());
    }

    @Transactional
    public void deleteTerm(UUID topicId, UUID termId) {
        term(topicId, termId);
        jdbc.update("DELETE FROM topic_terms WHERE topic_id=? AND term_id=?", topicId, termId);
        Integer links = jdbc.queryForObject("SELECT COUNT(*) FROM topic_terms WHERE term_id=?", Integer.class, termId);
        if (links != null && links == 0) jdbc.update("DELETE FROM glossary_terms WHERE id=?", termId);
    }

    public List<String> approvedKeyterms(UUID topicId) {
        find(topicId);
        return jdbc.query("SELECT gt.canonical FROM glossary_terms gt JOIN topic_terms tt ON tt.term_id=gt.id " +
                        "WHERE tt.topic_id=? AND gt.status='APPROVED' ORDER BY tt.priority DESC,gt.canonical LIMIT 100",
                (rs, row) -> rs.getString(1), topicId);
    }

    public List<CorrectionAlias> approvedAliases(UUID topicId) {
        return jdbc.query("SELECT ga.alias,ga.normalized_alias,gt.canonical FROM glossary_aliases ga " +
                        "JOIN glossary_terms gt ON gt.id=ga.term_id JOIN topic_terms tt ON tt.term_id=gt.id " +
                        "WHERE tt.topic_id=? AND gt.status='APPROVED' ORDER BY LENGTH(ga.alias) DESC",
                (rs, row) -> new CorrectionAlias(rs.getString("alias"), rs.getString("normalized_alias"),
                        rs.getString("canonical")), topicId);
    }

    private GlossaryTerm globalTerm(UUID termId) {
        List<GlossaryTerm> rows = jdbc.query(GLOBAL_TERM_SELECT + " WHERE gt.id=?", this::term, termId);
        if (rows.isEmpty()) throw notFound("TERM_NOT_FOUND", "Glossary term was not found");
        return rows.getFirst();
    }

    private GlossaryTerm term(ResultSet rs, int row) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        List<String> aliases = jdbc.query("SELECT alias FROM glossary_aliases WHERE term_id=? ORDER BY alias",
                (aliasRs, aliasRow) -> aliasRs.getString(1), id);
        return new GlossaryTerm(id, rs.getString("canonical"), rs.getString("meaning"), aliases,
                rs.getString("status"), rs.getString("source"));
    }

    private Topic topic(ResultSet rs, int row) throws SQLException {
        return new Topic(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("description"));
    }

    private List<String> normalizedAliases(List<String> aliases) {
        return aliases.stream().map(TopicService::normalize).sorted().toList();
    }

    private ApiException notFound(String code, String message) { return new ApiException(HttpStatus.NOT_FOUND, code, message); }
    private ApiException conflict(String code, String message) { return new ApiException(HttpStatus.CONFLICT, code, message); }

    public record AliasValue(String value, String normalized) {}
    public record CorrectionAlias(String alias, String normalizedAlias, String canonical) {}

    private static final String TERM_SELECT = "SELECT gt.id,gt.canonical,gt.meaning,gt.status,gt.source FROM glossary_terms gt JOIN topic_terms tt ON tt.term_id=gt.id";
    private static final String GLOBAL_TERM_SELECT = "SELECT gt.id,gt.canonical,gt.meaning,gt.status,gt.source FROM glossary_terms gt";
}
