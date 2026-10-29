package dev.aimeeting.api.provider.deepgram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.aimeeting.api.config.AiConfiguration;
import dev.aimeeting.api.meeting.MeetingRepository;
import dev.aimeeting.api.settings.AppSettings;
import dev.aimeeting.api.settings.SettingsRepository;
import dev.aimeeting.api.topic.TopicRepository;
import dev.aimeeting.api.usage.ledger.CostLedgerService;
import dev.aimeeting.api.usage.ledger.LedgerTotals;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.ObjectProvider;

class DeepgramLiveSessionFactoryTest {
    @Test void settingsDurationLimitRejectsAReconnectAtTheMeetingBoundary() {
        Fixture fixture = fixture(new BigDecimal("10.00"), 90);
        CompletableFuture<DeepgramMeetingPoc> opening = fixture.factory.open(UUID.randomUUID(), 5_400_000, ignored -> {});
        assertThatThrownBy(opening::join).hasCauseInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duration or budget limit");
    }

    @Test void budgetBoundsTheProviderReservationBeforeDispatch() {
        Fixture fixture = fixture(new BigDecimal("0.011"), 90);
        UUID meetingId = UUID.randomUUID();
        DeepgramMeetingPoc live = mock(DeepgramMeetingPoc.class);
        try (MockedStatic<DeepgramMeetingPoc> opening = mockStatic(DeepgramMeetingPoc.class)) {
            opening.when(() -> DeepgramMeetingPoc.openLive(anyString(), any(UUID.class), eq(meetingId), eq(0L),
                    eq(true), argThat(limits -> limits.maxDuration().equals(Duration.ofSeconds(60))
                            && limits.maximumEstimatedUsd().compareTo(new BigDecimal("0.011000")) == 0),
                    eq(new BigDecimal("3.00")), eq(5_400L), anyString(), any(MeetingRepository.class),
                    any(TopicRepository.class), any(CostLedgerService.class), any(), any(DeepgramStartupRecovery.class)))
                    .thenReturn(CompletableFuture.completedFuture(live));
            assertThat(fixture.factory.open(meetingId, 0, ignored -> {}).join()).isSameAs(live);
        }
    }

    @Test void zeroMonthlyBudgetExplainsRejectionWithoutOpeningProvider() {
        Fixture fixture = fixture(BigDecimal.ZERO, 240);
        try (MockedStatic<DeepgramMeetingPoc> provider = mockStatic(DeepgramMeetingPoc.class)) {
            assertThatThrownBy(() -> fixture.factory.open(UUID.randomUUID(), 0, ignored -> {}).join())
                    .hasMessageContaining("MONTHLY_BUDGET_EXHAUSTED");
            provider.verifyNoInteractions();
        }
    }

    @SuppressWarnings("unchecked")
    private Fixture fixture(BigDecimal monthlyBudget, int perMeetingMinutes) {
        AiConfiguration configuration = mock(AiConfiguration.class);
        when(configuration.maximumAudioSeconds()).thenReturn(14_400);
        when(configuration.maximumMeetingUsd()).thenReturn(new BigDecimal("3.00"));
        when(configuration.deepgramRatePerMinute()).thenReturn(new BigDecimal("0.011"));
        when(configuration.deepgramApiKey()).thenReturn("synthetic-key");
        when(configuration.priceVersion()).thenReturn("test-rates");
        SettingsRepository settings = mock(SettingsRepository.class);
        when(settings.get()).thenReturn(new AppSettings(monthlyBudget, 80, perMeetingMinutes, null, 0, true));
        CostLedgerService ledger = mock(CostLedgerService.class);
        when(ledger.totals(any())).thenReturn(LedgerTotals.zero());
        when(ledger.totalsForMeeting(any())).thenReturn(LedgerTotals.zero());
        ObjectProvider<DeepgramStartupRecovery> recovery = mock(ObjectProvider.class);
        when(recovery.getIfAvailable()).thenReturn(mock(DeepgramStartupRecovery.class));
        return new Fixture(new DeepgramLiveSessionFactory(configuration, mock(MeetingRepository.class),
                mock(TopicRepository.class), ledger, settings, recovery));
    }

    private record Fixture(DeepgramLiveSessionFactory factory) {}
}
