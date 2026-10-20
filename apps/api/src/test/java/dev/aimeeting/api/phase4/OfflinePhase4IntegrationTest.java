package dev.aimeeting.api.phase4;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aimeeting.api.auth.SessionService;
import dev.aimeeting.api.export.MeetingExport;
import dev.aimeeting.api.export.MeetingExportService;
import dev.aimeeting.api.meeting.Meeting;
import dev.aimeeting.api.meeting.MeetingRepository;
import dev.aimeeting.api.meeting.TranscriptSegment;
import dev.aimeeting.api.provider.SummaryProvider;
import dev.aimeeting.api.summary.MeetingSummary;
import dev.aimeeting.api.summary.SummaryService;
import dev.aimeeting.api.web.ApiException;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OfflinePhase4IntegrationTest {
    private static final String ORIGIN = "http://localhost:5173";
    private static final String TOKEN = "test-owner-token-00000000000000000";

    @Autowired MeetingRepository meetings;
    @Autowired MeetingExportService exports;
    @Autowired SummaryService summaries;
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc mvc;
    private Cookie session;

    @BeforeAll
    void login() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/session").header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"" + TOKEN + "\"}"))
                .andExpect(status().isOk()).andReturn();
        String setCookie = result.getResponse().getHeader("Set-Cookie");
        String prefix = SessionService.COOKIE_NAME + "=";
        session = new Cookie(SessionService.COOKIE_NAME,
                setCookie.substring(prefix.length(), setCookie.indexOf(';')));
    }

    @Test
    @Transactional
    void searchMatchesUnicodeAndOriginalTextWhileTreatingWildcardsLiterally() {
        Meeting unicode = meetings.create("한글 회의");
        Meeting transcript = meetings.create("Plain title");
        TranscriptSegment segment = meetings.insertFinal(transcript.id(), "search-original", 1, 0, 10,
                "Original needle", null);
        meetings.correct(transcript.id(), segment.id(), 0, "changed display", "USER", null, true);
        Meeting percent = meetings.create("Budget 100% review");
        Meeting underscore = meetings.create("literal_underbar");

        assertThat(meetings.search("한글")).extracting(Meeting::id).containsExactly(unicode.id());
        assertThat(meetings.search("needle")).extracting(Meeting::id).containsExactly(transcript.id());
        assertThat(meetings.search("%")).extracting(Meeting::id).containsExactly(percent.id());
        assertThat(meetings.search("_")).extracting(Meeting::id).containsExactly(underscore.id());
    }

    @Test
    @Transactional
    void titleUpdateUsesMeetingRevisionAndPreservesState() {
        Meeting meeting = meetings.create("Before");
        Meeting started = meetings.start(meeting.id());
        Meeting renamed = meetings.updateTitle(meeting.id(), "  After  ", started.revision());

        assertThat(renamed.title()).isEqualTo("After");
        assertThat(renamed.status()).isEqualTo("STARTED");
        assertThat(renamed.revision()).isEqualTo(started.revision() + 1);
        assertThatThrownBy(() -> meetings.updateTitle(meeting.id(), "Stale", started.revision()))
                .isInstanceOf(ApiException.class).hasMessageContaining("stale");
        assertThat(meetings.find(meeting.id()).title()).isEqualTo("After");
    }

    @Test
    @Transactional
    void jsonExportIncludesOriginalDisplayUnknownSpeakerEvidenceAndExplicitNullSummary() throws Exception {
        Meeting meeting = meetings.create("Export JSON");
        TranscriptSegment segment = meetings.insertFinal(meeting.id(), "export-event", 1, 5, 15,
                "source words", null);
        meetings.correct(meeting.id(), segment.id(), 0, "edited words", "USER", "clarity", true);

        MeetingExport withoutSummary = exports.snapshot(meeting.id());
        JsonNode initialJson = mapper.readTree(exports.json(withoutSummary));
        assertThat(initialJson.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(initialJson.has("summary")).isTrue();
        assertThat(initialJson.get("summary").isNull()).isTrue();
        assertThat(initialJson.at("/transcript/0/originalText").asText()).isEqualTo("source words");
        assertThat(initialJson.at("/transcript/0/displayText").asText()).isEqualTo("edited words");
        assertThat(initialJson.at("/transcript/0/speaker").asText()).isEqualTo("unknown");
        assertThat(initialJson.at("/transcript/0/userLocked").asBoolean()).isTrue();

        meetings.stop(meeting.id());
        summaries.generate(meeting.id());
        JsonNode summarized = mapper.readTree(exports.json(exports.snapshot(meeting.id())));
        assertThat(summarized.at("/summary/content/sourceSegmentIds/0").asText()).isEqualTo(segment.id().toString());
        assertThat(summarized.at("/summary/stale").asBoolean()).isFalse();
        assertThat(summarized.toString()).doesNotContain("path", "key");
    }

    @Test
    @Transactional
    void markdownEscapesHtmlFencesAndMarkdownControlCharacters() {
        Meeting meeting = meetings.create("<script>alert(1)</script> ``` # title");
        meetings.insertFinal(meeting.id(), "markdown-event", 1, 0, 20,
                "<img src=x>\n```danger\n[link](javascript:alert(1))", null);

        String markdown = new String(exports.markdown(exports.snapshot(meeting.id())), StandardCharsets.UTF_8);

        assertThat(markdown).contains("&lt;script&gt;", "\\`\\`\\`", "unknown", "&lt;img src=x&gt;",
                "Segment ID: ", "Transcript status: FINAL", "Speaker status: UNKNOWN");
        assertThat(markdown).doesNotContain("<script>", "<img", "\n```danger", "[link](javascript:");

        SummaryProvider.ActionItem assigned = new SummaryProvider.ActionItem("Ship", "Min", "2026-10-20", List.of());
        SummaryProvider.ActionItem unknown = new SummaryProvider.ActionItem("Review", null, null, List.of());
        MeetingSummary summary = new MeetingSummary(UUID.randomUUID(), meeting.id(), 1, "MOCK",
                new SummaryProvider.Summary("Summary", List.of(), List.of(), List.of(assigned, unknown), List.of()), false);
        MeetingExport withActions = new MeetingExport(1, meeting, List.of(), summary);
        String actionMarkdown = new String(exports.markdown(withActions), StandardCharsets.UTF_8);
        assertThat(actionMarkdown).contains("assignee: Min", "due date: 2026\\-10\\-20",
                "assignee: unknown", "due date: unknown");
    }

    @Test
    void exportEndpointRequiresAuthenticationAndReturnsAttachmentsAndBoundedErrors() throws Exception {
        Meeting meeting = meetings.create("Attachment export");

        mvc.perform(get("/api/v1/meetings/{id}/export", meeting.id()).queryParam("format", "json"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/meetings/{id}/export", meeting.id()).cookie(session).queryParam("format", "xml"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_EXPORT_FORMAT"));
        mvc.perform(get("/api/v1/meetings/{id}/export", java.util.UUID.randomUUID()).cookie(session)
                        .queryParam("format", "json"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MEETING_NOT_FOUND"));
        mvc.perform(get("/api/v1/meetings/{id}/export", meeting.id()).cookie(session).queryParam("format", "json"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"meeting-" + meeting.id() + ".json\""))
                .andExpect(jsonPath("$.schemaVersion").value(1));
        mvc.perform(get("/api/v1/meetings").cookie(session).queryParam("q", "x".repeat(201)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_QUERY"));
        mvc.perform(get("/api/v1/meetings").cookie(session).queryParam("q", " ".repeat(201)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_QUERY"));

        MvcResult renamed = mvc.perform(patch("/api/v1/meetings/{id}", meeting.id()).cookie(session)
                        .header("Origin", ORIGIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\" Renamed through API \",\"revision\":" + meeting.revision() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Renamed through API"))
                .andExpect(jsonPath("$.status").value(meeting.status()))
                .andReturn();
        int newRevision = mapper.readTree(renamed.getResponse().getContentAsByteArray()).path("revision").asInt();
        assertThat(newRevision).isEqualTo(meeting.revision() + 1);
        mvc.perform(patch("/api/v1/meetings/{id}", meeting.id()).cookie(session)
                        .header("Origin", ORIGIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Stale API edit\",\"revision\":" + meeting.revision() + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_REVISION"));
        mvc.perform(patch("/api/v1/meetings/{id}", meeting.id()).cookie(session)
                        .header("Origin", ORIGIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Missing revision\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
}
