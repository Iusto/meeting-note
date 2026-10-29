package dev.aimeeting.api.provider.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.aimeeting.api.correction.CorrectionController;
import dev.aimeeting.api.correction.CorrectionService;
import dev.aimeeting.api.correction.CorrectionSuggestion;
import dev.aimeeting.api.summary.MeetingSummary;
import dev.aimeeting.api.summary.SummaryController;
import dev.aimeeting.api.summary.SummaryService;
import dev.aimeeting.api.topic.GlossaryTerm;
import dev.aimeeting.api.topic.TopicController;
import dev.aimeeting.api.topic.TopicService;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class AiControllerRoutingTest {
    private final UUID subjectId = UUID.randomUUID();
    private final MeetingAiApplicationService ai = mock(MeetingAiApplicationService.class);

    @Test
    void paidModeRoutesEveryGenerationEndpointThroughApplicationService() {
        SummaryService summaries = mock(SummaryService.class);
        CorrectionService corrections = mock(CorrectionService.class);
        TopicService topics = mock(TopicService.class);
        MeetingSummary summary = mock(MeetingSummary.class);
        List<CorrectionSuggestion> suggestions = List.of(mock(CorrectionSuggestion.class));
        List<GlossaryTerm> terms = List.of(mock(GlossaryTerm.class));
        when(ai.enabled()).thenReturn(true);
        when(ai.generateSummary(subjectId)).thenReturn(CompletableFuture.completedFuture(summary));
        when(ai.generateCorrections(subjectId)).thenReturn(CompletableFuture.completedFuture(suggestions));
        when(ai.generateTopicCandidates(subjectId)).thenReturn(CompletableFuture.completedFuture(terms));

        assertThat(new SummaryController(summaries, ai).generate(subjectId).join()).isSameAs(summary);
        assertThat(new CorrectionController(corrections, ai).generate(subjectId).join()).isSameAs(suggestions);
        assertThat(new TopicController(topics, ai).suggest(subjectId).join()).isSameAs(terms);

        verify(ai).generateSummary(subjectId);
        verify(ai).generateCorrections(subjectId);
        verify(ai).generateTopicCandidates(subjectId);
        verifyNoInteractions(summaries, corrections, topics);
    }

    @Test
    void defaultModeKeepsExistingOfflineProviders() {
        SummaryService summaries = mock(SummaryService.class);
        CorrectionService corrections = mock(CorrectionService.class);
        TopicService topics = mock(TopicService.class);
        MeetingSummary summary = mock(MeetingSummary.class);
        List<CorrectionSuggestion> suggestions = List.of();
        List<GlossaryTerm> terms = List.of();
        when(ai.enabled()).thenReturn(false);
        when(summaries.generate(subjectId)).thenReturn(summary);
        when(corrections.generate(subjectId)).thenReturn(suggestions);
        when(topics.suggest(subjectId)).thenReturn(terms);

        assertThat(new SummaryController(summaries, ai).generate(subjectId).join()).isSameAs(summary);
        assertThat(new CorrectionController(corrections, ai).generate(subjectId).join()).isSameAs(suggestions);
        assertThat(new TopicController(topics, ai).suggest(subjectId).join()).isSameAs(terms);

        verify(summaries).generate(subjectId);
        verify(corrections).generate(subjectId);
        verify(topics).suggest(subjectId);
    }
}
