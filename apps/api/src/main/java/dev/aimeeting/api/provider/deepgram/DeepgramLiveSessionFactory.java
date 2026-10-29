package dev.aimeeting.api.provider.deepgram;

import dev.aimeeting.api.config.AiConfiguration;
import dev.aimeeting.api.meeting.MeetingRepository;
import dev.aimeeting.api.provider.SpeechEvent;
import dev.aimeeting.api.settings.AppSettings;
import dev.aimeeting.api.settings.SettingsRepository;
import dev.aimeeting.api.topic.TopicRepository;
import dev.aimeeting.api.usage.ledger.CostLedgerService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Creates one paid Deepgram session without exposing provider credentials to the browser. */
@Component
public final class DeepgramLiveSessionFactory {
    private final AiConfiguration configuration;
    private final MeetingRepository meetings;
    private final TopicRepository topics;
    private final CostLedgerService ledger;
    private final SettingsRepository settings;
    private final ObjectProvider<DeepgramStartupRecovery> recovery;

    public DeepgramLiveSessionFactory(AiConfiguration configuration, MeetingRepository meetings,
                                      TopicRepository topics, CostLedgerService ledger,
                                      SettingsRepository settings, ObjectProvider<DeepgramStartupRecovery> recovery) {
        this.configuration = configuration;
        this.meetings = meetings;
        this.topics = topics;
        this.ledger = ledger;
        this.settings = settings;
        this.recovery = recovery;
    }

    public CompletableFuture<DeepgramMeetingPoc> open(UUID meetingId, long audioOffsetMs,
                                                       Consumer<SpeechEvent> events) {
        configuration.requireSpeech();
        AppSettings currentSettings = settings.get();
        long settingsMaximumSeconds = Math.multiplyExact((long) currentSettings.perMeetingMaxMinutes(), 60L);
        long maximumSeconds = Math.min(configuration.maximumAudioSeconds(), settingsMaximumSeconds);
        long elapsedSeconds = Math.min(maximumSeconds, (audioOffsetMs + 999) / 1_000);
        long remainingSeconds = maximumSeconds - elapsedSeconds;
        BigDecimal rate = configuration.deepgramRatePerMinute();
        BigDecimal availableMonthlyUsd = currentSettings.monthlyBudgetUsd()
                .subtract(ledger.totals(YearMonth.now(ZoneOffset.UTC)).budgetCommittedUsd())
                .max(BigDecimal.ZERO);
        BigDecimal availableMeetingUsd = configuration.maximumMeetingUsd()
                .subtract(ledger.totalsForMeeting(meetingId).budgetCommittedUsd()).max(BigDecimal.ZERO);
        BigDecimal availableUsd = availableMonthlyUsd.min(availableMeetingUsd);
        long budgetSeconds = availableUsd.multiply(BigDecimal.valueOf(60))
                .divide(rate, 0, RoundingMode.DOWN).longValueExact();
        remainingSeconds = Math.min(remainingSeconds, budgetSeconds);
        if (availableMonthlyUsd.signum() <= 0) {
            return CompletableFuture.failedFuture(new IllegalStateException("MONTHLY_BUDGET_EXHAUSTED"));
        }
        if (availableMeetingUsd.signum() <= 0) {
            return CompletableFuture.failedFuture(new IllegalStateException("MEETING_BUDGET_EXHAUSTED"));
        }
        if (remainingSeconds <= 0) {
            return CompletableFuture.failedFuture(new IllegalStateException("Meeting audio duration or budget limit reached"));
        }
        BigDecimal sessionMaximumUsd = rate.multiply(BigDecimal.valueOf(remainingSeconds))
                .divide(BigDecimal.valueOf(60), 6, RoundingMode.UP);
        PocLimits limits = PocLimits.production(Duration.ofSeconds(remainingSeconds), sessionMaximumUsd, rate);
        DeepgramStartupRecovery ownership = recovery.getIfAvailable();
        if (ownership == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Deepgram recovery ownership is unavailable"));
        }
        return DeepgramMeetingPoc.openLive(configuration.deepgramApiKey(), UUID.randomUUID(), meetingId,
                audioOffsetMs, true, limits, configuration.maximumMeetingUsd(), maximumSeconds,
                configuration.priceVersion(), meetings, topics, ledger, events, ownership);
    }
}
