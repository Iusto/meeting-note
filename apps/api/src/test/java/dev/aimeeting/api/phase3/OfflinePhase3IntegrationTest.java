package dev.aimeeting.api.phase3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aimeeting.api.correction.CorrectionService;
import dev.aimeeting.api.correction.CorrectionSuggestion;
import dev.aimeeting.api.meeting.InputSource;
import dev.aimeeting.api.meeting.Meeting;
import dev.aimeeting.api.meeting.MeetingRepository;
import dev.aimeeting.api.meeting.TranscriptSegment;
import dev.aimeeting.api.summary.MeetingSummary;
import dev.aimeeting.api.summary.SummaryService;
import dev.aimeeting.api.topic.GlossaryTerm;
import dev.aimeeting.api.topic.Topic;
import dev.aimeeting.api.topic.TopicService;
import dev.aimeeting.api.web.ApiException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Transactional
class OfflinePhase3IntegrationTest {
    @Autowired TopicService topics;
    @Autowired MeetingRepository meetings;
    @Autowired SummaryService summaries;
    @Autowired CorrectionService corrections;
    @Autowired JdbcTemplate jdbc;

    @Test
    void mockSuggestionsRemainPendingUntilOneTermIsExplicitlyApproved() {
        Topic topic = topics.create("MES glossary", "offline fixture");
        List<GlossaryTerm> suggested = topics.suggest(topic.id());

        assertThat(suggested).extracting(GlossaryTerm::canonical).containsExactly("MES", "LOT");
        assertThat(suggested).allMatch(term -> term.status().equals("PENDING") && term.source().equals("MOCK"));
        assertThat(topics.keyterms(topic.id())).isEmpty();

        GlossaryTerm lot = suggested.get(1);
        GlossaryTerm approved = topics.updateTerm(topic.id(), lot.id(), null, null, null, "APPROVED");
        assertThat(approved.status()).isEqualTo("APPROVED");
        assertThat(topics.keyterms(topic.id())).containsExactly("LOT");
        assertThat(topics.suggest(topic.id())).extracting(GlossaryTerm::id)
                .containsExactlyElementsOf(suggested.stream().map(GlossaryTerm::id).toList());
    }

    @Test
    void aliasesAreNormalizedUniqueAndTopicCrudIsBounded() {
        Topic topic = topics.create("Quality", null);
        topics.createTerm(topic.id(), "WIP", "Work in process", List.of("더블유 아이 피"), "PENDING", "MANUAL");
        assertThatThrownBy(() -> topics.createTerm(topic.id(), "Other", "Other meaning",
                List.of("  더블유   아이 피  "), "PENDING", "MANUAL"))
                .isInstanceOf(ApiException.class).hasMessageContaining("already exists");
        Topic renamed = topics.update(topic.id(), "Quality control", "updated");
        assertThat(renamed.name()).isEqualTo("Quality control");
    }

    @Test
    void deletingLastTopicLinkRemovesOrphanSharedTerm() {
        Topic first = topics.create("First", null);
        Topic second = topics.create("Second", null);
        GlossaryTerm shared = topics.createTerm(first.id(), "BOM", "Bill of materials",
                List.of("비오엠"), "APPROVED", "MANUAL");
        topics.createTerm(second.id(), "BOM", "Bill of materials", List.of("비오엠"), "APPROVED", "MANUAL");
        topics.delete(first.id());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM glossary_terms WHERE id=?", Integer.class, shared.id())).isEqualTo(1);
        topics.delete(second.id());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM glossary_terms WHERE id=?", Integer.class, shared.id())).isZero();
    }

    @Test
    void summaryIsExtractiveVersionedAndBecomesStaleAfterTranscriptRevision() {
        Meeting meeting = meetings.create("Summary", InputSource.MOCK, null);
        meetings.start(meeting.id());
        TranscriptSegment segment = meetings.insertFinal(meeting.id(), "summary-event", 0, 0, 10,
                "배포 일정은 목요일입니다.", null);

        assertThatThrownBy(() -> summaries.generate(meeting.id()))
                .isInstanceOf(ApiException.class).hasMessageContaining("stopped");
        meetings.stop(meeting.id());
        MeetingSummary first = summaries.generate(meeting.id());
        assertThat(first.version()).isEqualTo(1);
        assertThat(first.provider()).isEqualTo("MOCK");
        assertThat(first.content().summary()).isEqualTo("배포 일정은 목요일입니다.");
        assertThat(first.content().sourceSegmentIds()).containsExactly(segment.id());
        assertThat(first.content().decisions()).isEmpty();
        assertThat(first.content().actionItems()).isEmpty();
        assertThat(first.stale()).isFalse();

        meetings.correct(meeting.id(), segment.id(), 0, "배포 일정은 금요일입니다.", "USER", "manual", true);
        assertThat(summaries.latest(meeting.id()).stale()).isTrue();
        MeetingSummary second = summaries.generate(meeting.id());
        assertThat(second.version()).isEqualTo(2);
        assertThat(second.content().summary()).isEqualTo("배포 일정은 금요일입니다.");
        assertThat(second.stale()).isFalse();
    }

    @Test
    void summaryRejectsStoppedMeetingWithoutFinalTranscript() {
        Meeting meeting = meetings.create("Empty", InputSource.MOCK, null);
        meetings.stop(meeting.id());
        assertThatThrownBy(() -> summaries.generate(meeting.id()))
                .isInstanceOf(ApiException.class).hasMessageContaining("finalized transcript");
    }

    @Test
    void approvedAliasCreatesReviewOnlyPatchAndAmbiguousLotteryWordIsPreserved() {
        Topic topic = topics.create("Production", null);
        topics.createTerm(topic.id(), "LOT", "생산 추적 단위", List.of("로떠", "로또", "로떠٢", "로뜨２"), "APPROVED", "MANUAL");
        topics.createTerm(topic.id(), "LOT２", "숫자가 포함되어 자동 교정하지 않는 용어", List.of("로뛔"), "APPROVED", "MANUAL");
        Meeting meeting = meetings.create("Correction", InputSource.MOCK, topic.id());
        meetings.start(meeting.id());
        TranscriptSegment segment = meetings.insertFinal(meeting.id(), "correction-event", 0, 0, 10,
                "로떠 123과 로또 번호, 로떠-123, 로떠ABC, 로떠٢, 로뜨２, 로뛔를 구분합니다.", null);
        meetings.stop(meeting.id());

        List<CorrectionSuggestion> proposals = corrections.generate(meeting.id());
        assertThat(proposals).hasSize(1);
        CorrectionSuggestion proposal = proposals.getFirst();
        assertThat(proposal.originalSpan()).isEqualTo("로떠");
        assertThat(proposal.replacement()).isEqualTo("LOT");
        assertThat(meetings.findSegment(segment.id()).displayText()).isEqualTo("로떠 123과 로또 번호, 로떠-123, 로떠ABC, 로떠٢, 로뜨２, 로뛔를 구분합니다.");

        CorrectionSuggestion applied = corrections.review(meeting.id(), proposal.id(), "APPLIED");
        assertThat(applied.state()).isEqualTo("APPLIED");
        TranscriptSegment corrected = meetings.findSegment(segment.id());
        assertThat(corrected.originalText()).isEqualTo("로떠 123과 로또 번호, 로떠-123, 로떠ABC, 로떠٢, 로뜨２, 로뛔를 구분합니다.");
        assertThat(corrected.displayText()).isEqualTo("LOT 123과 로또 번호, 로떠-123, 로떠ABC, 로떠٢, 로뜨２, 로뛔를 구분합니다.");
        assertThat(corrected.revision()).isEqualTo(1);
    }

    @Test
    void applyingProposalAfterRevisionChangeReturnsStaleConflict() {
        Topic topic = topics.create("Stale", null);
        topics.createTerm(topic.id(), "LOT", "생산 추적 단위", List.of("로뜨"), "APPROVED", "MANUAL");
        Meeting meeting = meetings.create("Stale correction", InputSource.MOCK, topic.id());
        meetings.start(meeting.id());
        TranscriptSegment segment = meetings.insertFinal(meeting.id(), "stale-event", 0, 0, 10, "로뜨 확인", null);
        meetings.stop(meeting.id());
        CorrectionSuggestion proposal = corrections.generate(meeting.id()).getFirst();
        meetings.correct(meeting.id(), segment.id(), 0, "사용자 수정", "USER", null, true);

        assertThatThrownBy(() -> corrections.review(meeting.id(), proposal.id(), "APPLIED"))
                .isInstanceOf(ApiException.class).hasMessageContaining("changed");
        assertThat(corrections.list(meeting.id()).getFirst().state()).isEqualTo("PENDING");
    }
}
