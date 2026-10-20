package dev.aimeeting.api.usage.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aimeeting.api.web.ApiException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.YearMonth;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:cost-ledger;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class CostLedgerIntegrationTest {
    private static final Clock OCTOBER = Clock.fixed(Instant.parse("2026-10-18T04:00:00Z"), ZoneOffset.UTC);
    private static final Clock NOVEMBER = Clock.fixed(Instant.parse("2026-11-01T00:00:01Z"), ZoneOffset.UTC);

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    private CostLedgerService ledger;
    private UUID meetingId;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM ai_usage_records");
        jdbc.update("DELETE FROM meetings");
        jdbc.update("""
                UPDATE app_settings SET monthly_budget_usd=10.00,warn_at_percent=80,
                       per_meeting_max_minutes=240,recording_retention_days=NULL,revision=0 WHERE id=1
                """);
        meetingId = UUID.randomUUID();
        jdbc.update("INSERT INTO meetings(id,title,input_source,status,revision) VALUES (?,?, 'MOCK','CREATED',0)",
                meetingId, "Synthetic ledger test");
        ledger = new CostLedgerService(jdbc, transactions, OCTOBER);
    }

    @Test
    void zeroBudgetBlocksWithoutCreatingARecord() {
        jdbc.update("UPDATE app_settings SET monthly_budget_usd=0 WHERE id=1");

        assertThatThrownBy(() -> reserve(UUID.randomUUID(), "1.00"))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("MONTHLY_BUDGET_EXCEEDED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_usage_records", Integer.class)).isZero();
    }

    @Test
    void reservationReplayIsIdempotentButMismatchedPayloadConflicts() {
        UUID requestId = UUID.randomUUID();
        LedgerEntry first = reserve(requestId, "0.1000001");
        LedgerEntry replay = reserve(requestId, "0.1000001");

        assertThat(first.reservedUsd()).isEqualByComparingTo("0.100001");
        assertThat(replay).isEqualTo(first);
        assertThatThrownBy(() -> reserve(requestId, "0.20"))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("RESERVATION_MISMATCH"));
    }

    @Test
    void concurrentReservationsCannotOverspendBudget() throws Exception {
        jdbc.update("UPDATE app_settings SET monthly_budget_usd=1.00 WHERE id=1");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = pool.submit(() -> concurrentReserve(ready, start));
            Future<Boolean> second = pool.submit(() -> concurrentReserve(ready, start));
            ready.await();
            start.countDown();

            assertThat(new boolean[]{first.get(), second.get()}).containsExactlyInAnyOrder(true, false);
        }
        assertThat(ledger.totals(YearMonth.of(2026, 10)).budgetCommittedUsd()).isEqualByComparingTo("0.750000");
    }

    @Test
    void dispatchIsOneShotAndOnlyUnsentReservationCanBeReleased() {
        UUID releasedId = UUID.randomUUID();
        reserve(releasedId, "1");
        assertThat(ledger.release(releasedId).state()).isEqualTo(UsageState.RELEASED);
        assertThat(ledger.release(releasedId).state()).isEqualTo(UsageState.RELEASED);

        UUID dispatchedId = UUID.randomUUID();
        reserve(dispatchedId, "2");
        assertThat(ledger.startDispatch(dispatchedId).state()).isEqualTo(UsageState.DISPATCHED);
        assertThatThrownBy(() -> ledger.startDispatch(dispatchedId)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> ledger.release(dispatchedId)).isInstanceOf(ApiException.class);
        assertThat(ledger.markUncertain(dispatchedId).state()).isEqualTo(UsageState.UNCERTAIN);
        assertThatThrownBy(() -> ledger.release(dispatchedId))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("RESERVATION_CANNOT_BE_RELEASED"));

        LedgerTotals totals = ledger.totals(YearMonth.of(2026, 10));
        assertThat(totals.reservedUsd()).isEqualByComparingTo("2.000000");
        assertThat(totals.unreconciledCount()).isOne();
        assertThat(totals.actualUsd()).isNull();
    }

    @Test
    void settlementAndReconciliationAreExactIdempotentAndOverrunStaysCommitted() {
        jdbc.update("UPDATE app_settings SET monthly_budget_usd=2.00 WHERE id=1");
        UUID requestId = UUID.randomUUID();
        reserve(requestId, "1");
        ledger.startDispatch(requestId);
        Settlement usage = new Settlement(new BigDecimal("2.500000"), 90L, 20L, 10L);

        assertThat(ledger.settle(requestId, usage).estimatedUsd()).isEqualByComparingTo("2.5");
        assertThat(ledger.settle(requestId, usage).state()).isEqualTo(UsageState.SETTLED);
        assertThatThrownBy(() -> ledger.settle(requestId,
                new Settlement(new BigDecimal("1.4"), 90L, 20L, 10L))).isInstanceOf(ApiException.class);
        assertThat(ledger.reconcile(requestId, new BigDecimal("2.75"), "provider-report").actualUsd())
                .isEqualByComparingTo("2.75");
        assertThat(ledger.reconcile(requestId, new BigDecimal("2.75"), "provider-report").state())
                .isEqualTo(UsageState.SETTLED);
        assertThatThrownBy(() -> ledger.reconcile(requestId, new BigDecimal("1.20"), "provider-report"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> reserve(UUID.randomUUID(), "0.01"))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("MONTHLY_BUDGET_EXCEEDED"));

        LedgerTotals totals = ledger.totals(YearMonth.of(2026, 10));
        assertThat(totals.budgetCommittedUsd()).isEqualByComparingTo("2.750000");
        assertThat(totals.actualUsd()).isEqualByComparingTo("2.750000");
        assertThat(totals.reservedUsd()).isZero();
    }

    @Test
    void uncertainUsageAcceptsBillingEvidenceBeforeMeasuredUsageArrives() {
        UUID requestId = UUID.randomUUID();
        reserve(requestId, "1");
        ledger.startDispatch(requestId);
        ledger.markUncertain(requestId);

        LedgerEntry reconciled = ledger.reconcile(requestId, new BigDecimal("1.50"), "provider invoice");
        assertThat(reconciled.state()).isEqualTo(UsageState.UNCERTAIN);
        assertThat(reconciled.estimatedUsd()).isNull();
        assertThat(reconciled.actualUsd()).isEqualByComparingTo("1.50");
        assertThat(ledger.reconcile(requestId, new BigDecimal("1.50"), "provider invoice")).isEqualTo(reconciled);
        assertThatThrownBy(() -> ledger.reconcile(requestId, new BigDecimal("1.40"), "provider invoice"))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("RECONCILIATION_MISMATCH"));

        LedgerTotals uncertain = ledger.totals(YearMonth.of(2026, 10));
        assertThat(uncertain.reservedUsd()).isEqualByComparingTo("1");
        assertThat(uncertain.budgetCommittedUsd()).isEqualByComparingTo("1.50");
        assertThat(uncertain.actualUsd()).isEqualByComparingTo("1.50");

        LedgerEntry settled = ledger.settle(requestId, new Settlement(new BigDecimal("1.25"), 120L, 10L, 5L));
        assertThat(settled.state()).isEqualTo(UsageState.SETTLED);
        assertThat(settled.actualUsd()).isEqualByComparingTo("1.50");
        assertThat(ledger.totals(YearMonth.of(2026, 10)).budgetCommittedUsd()).isEqualByComparingTo("1.50");
    }

    @Test
    void dispatchRechecksLoweredBudgetAndDurationWithoutLosingReservation() {
        UUID requestId = UUID.randomUUID();
        reserve(requestId, "1");

        jdbc.update("UPDATE app_settings SET monthly_budget_usd=0 WHERE id=1");
        assertThatThrownBy(() -> ledger.startDispatch(requestId))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("MONTHLY_BUDGET_EXCEEDED"));
        assertThat(ledger.totals(YearMonth.of(2026, 10)).reservedUsd()).isEqualByComparingTo("1");

        jdbc.update("UPDATE app_settings SET monthly_budget_usd=10,per_meeting_max_minutes=1 WHERE id=1");
        assertThatThrownBy(() -> ledger.startDispatch(requestId))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("AUDIO_LIMIT_EXCEEDED"));
        assertThat(reserve(requestId, "1").state()).isEqualTo(UsageState.RESERVED);
    }

    @Test
    void activeCallerTransactionIsRejectedBeforeRequiresNewEvenWhenItHoldsPrerequisiteLocks() {
        UUID requestId = UUID.randomUUID();
        TransactionTemplate outer = new TransactionTemplate(transactions);

        outer.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT id FROM app_settings WHERE id=1 FOR UPDATE", Integer.class);
            jdbc.queryForObject("SELECT id FROM meetings WHERE id=? FOR UPDATE", UUID.class, meetingId);
            assertThatThrownBy(() -> ledger.reserve(requestId, request(meetingId, "1")))
                    .isInstanceOfSatisfying(IllegalStateException.class,
                            error -> assertThat(error.getMessage()).contains("outside an active transaction"));
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_usage_records WHERE id=?", Integer.class, requestId))
                .isZero();

        ledger.reserve(requestId, request(meetingId, "1"));
        ledger.startDispatch(requestId);
        assertThatThrownBy(() -> ledger.startDispatch(requestId))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("DISPATCH_ALREADY_CLAIMED"));
        assertThatThrownBy(() -> ledger.release(requestId))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("RESERVATION_CANNOT_BE_RELEASED"));
        assertThat(ledger.markUncertain(requestId).state()).isEqualTo(UsageState.UNCERTAIN);
    }

    @Test
    void oldMonthReservationCannotDispatchAndUnknownChargeKeepsItsReservation() {
        UUID requestId = UUID.randomUUID();
        reserve(requestId, "3");

        CostLedgerService nextMonth = new CostLedgerService(jdbc, transactions, NOVEMBER);
        assertThatThrownBy(() -> nextMonth.startDispatch(requestId))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("RESERVATION_MONTH_EXPIRED"));
        UUID novemberRequest = UUID.randomUUID();
        nextMonth.reserve(novemberRequest, request(meetingId, "2"));
        assertThat(ledger.totals(YearMonth.of(2026, 10)).budgetCommittedUsd()).isEqualByComparingTo("3");
        assertThat(nextMonth.totals(YearMonth.of(2026, 11)).budgetCommittedUsd()).isEqualByComparingTo("2");
        assertThat(nextMonth.totals(YearMonth.of(2026, 11)).requestCount()).isOne();
    }

    @Test
    void meetingDeletionDoesNotEraseAccountingOrRequestFingerprint() {
        UUID requestId = UUID.randomUUID();
        reserve(requestId, "0.75");
        jdbc.update("DELETE FROM meetings WHERE id=?", meetingId);

        assertThat(ledger.totals(YearMonth.of(2026, 10)).requestCount()).isOne();
        assertThat(ledger.byMeeting(YearMonth.of(2026, 10))).containsKey(meetingId);
        assertThat(reserve(requestId, "0.75").meetingId()).isEqualTo(meetingId);
        assertThatThrownBy(() -> ledger.startDispatch(requestId))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("MEETING_NOT_FOUND"));
    }

    private LedgerEntry reserve(UUID requestId, String usd) {
        return ledger.reserve(requestId, request(meetingId, usd));
    }

    private ReservationRequest request(UUID targetMeetingId, String usd) {
        return new ReservationRequest(targetMeetingId, "openai", "gpt-test", "summary",
                "test-prices-v1", new BigDecimal(usd), 120);
    }

    private boolean concurrentReserve(CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            reserve(UUID.randomUUID(), "0.75");
            return true;
        } catch (ApiException error) {
            assertThat(error.code()).isEqualTo("MONTHLY_BUDGET_EXCEEDED");
            return false;
        }
    }
}
