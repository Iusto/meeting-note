package dev.aimeeting.api.usage.ledger;

import dev.aimeeting.api.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CostLedgerService {
    private static final int MONEY_SCALE = 6;
    private static final BigDecimal MAX_MONEY = new BigDecimal("1000000.000000");
    private static final int PROVIDER_MAX = 40;
    private static final int MODEL_MAX = 100;
    private static final int OPERATION_MAX = 60;
    private static final int PRICE_VERSION_MAX = 100;
    private static final int ACTUAL_SOURCE_MAX = 100;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate requiresNew;
    private final Clock clock;

    @Autowired
    public CostLedgerService(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this(jdbc, transactions, Clock.systemUTC());
    }

    CostLedgerService(JdbcTemplate jdbc, PlatformTransactionManager transactions, Clock clock) {
        this.jdbc = jdbc;
        this.clock = Objects.requireNonNull(clock);
        this.requiresNew = new TransactionTemplate(transactions);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public LedgerEntry reserve(UUID requestId, ReservationRequest input) {
        rejectActiveCallerTransaction();
        Objects.requireNonNull(requestId, "requestId");
        ReservationRequest request = validate(input);
        return requiresNew.execute(status -> reserveInTransaction(requestId, request, null, 0));
    }

    /** All reconnects and LLM requests share one meeting allowance, across UTC months. */
    public LedgerEntry reserveBounded(UUID requestId, ReservationRequest input,
                                     BigDecimal maximumMeetingUsd, long maximumMeetingSeconds) {
        rejectActiveCallerTransaction();
        Objects.requireNonNull(requestId, "requestId");
        ReservationRequest request = validate(input);
        if (request.meetingId() == null || maximumMeetingSeconds <= 0) {
            throw bad("INVALID_RESERVATION", "A bounded meeting reservation requires a meeting and time limit");
        }
        BigDecimal cap = boundedMoney(maximumMeetingUsd, "maximumMeetingUsd", true, false);
        return requiresNew.execute(status -> reserveInTransaction(requestId, request, cap, maximumMeetingSeconds));
    }

    public LedgerEntry startDispatch(UUID requestId) {
        rejectActiveCallerTransaction();
        Objects.requireNonNull(requestId, "requestId");
        return requiresNew.execute(status -> {
            lockSettings();
            LedgerEntry entry = findLocked(requestId);
            requireState(entry, UsageState.RESERVED, "DISPATCH_ALREADY_CLAIMED");
            YearMonth current = YearMonth.from(OffsetDateTime.now(clock));
            if (!entry.reservationMonth().equals(current)) {
                throw conflict("RESERVATION_MONTH_EXPIRED", "Reservation must be recreated in the current UTC month");
            }
            lockSubject(entry.meetingId(), entry.topicId());
            Integer maxMinutes = jdbc.queryForObject("SELECT per_meeting_max_minutes FROM app_settings WHERE id=1",
                    Integer.class);
            if (maxMinutes == null || entry.maximumAudioSeconds() > Math.multiplyExact(maxMinutes.longValue(), 60L)) {
                throw bad("AUDIO_LIMIT_EXCEEDED", "maximumAudioSeconds exceeds the current per-meeting limit");
            }
            BigDecimal budget = monthlyBudget();
            if (budget.signum() == 0 || committed(entry.reservationMonth()).compareTo(budget) > 0) {
                throw budgetExceeded();
            }
            updateState(requestId, UsageState.DISPATCHED);
            return find(requestId);
        });
    }

    public LedgerEntry release(UUID requestId) {
        rejectActiveCallerTransaction();
        Objects.requireNonNull(requestId, "requestId");
        return requiresNew.execute(status -> {
            lockSettings();
            LedgerEntry entry = findLocked(requestId);
            if (entry.state() == UsageState.RELEASED) return entry;
            requireState(entry, UsageState.RESERVED, "RESERVATION_CANNOT_BE_RELEASED");
            updateState(requestId, UsageState.RELEASED);
            return find(requestId);
        });
    }

    public LedgerEntry markUncertain(UUID requestId) {
        rejectActiveCallerTransaction();
        Objects.requireNonNull(requestId, "requestId");
        return requiresNew.execute(status -> {
            lockSettings();
            LedgerEntry entry = findLocked(requestId);
            if (entry.state() == UsageState.UNCERTAIN) return entry;
            requireState(entry, UsageState.DISPATCHED, "USAGE_CANNOT_BE_MARKED_UNCERTAIN");
            updateState(requestId, UsageState.UNCERTAIN);
            return find(requestId);
        });
    }

    public LedgerEntry settle(UUID requestId, Settlement input) {
        rejectActiveCallerTransaction();
        Objects.requireNonNull(requestId, "requestId");
        Settlement settlement = validate(input);
        return requiresNew.execute(status -> {
            lockSettings();
            LedgerEntry entry = findLocked(requestId);
            if (entry.state() == UsageState.SETTLED) {
                if (sameSettlement(entry, settlement)) return entry;
                throw conflict("SETTLEMENT_MISMATCH", "Request was already settled with different usage");
            }
            if (entry.state() != UsageState.DISPATCHED && entry.state() != UsageState.UNCERTAIN) {
                throw conflict("USAGE_CANNOT_BE_SETTLED", "Only dispatched or uncertain usage can be settled");
            }
            jdbc.update("""
                    UPDATE ai_usage_records
                       SET state='SETTLED',estimated_usd=?,measured_audio_seconds=?,input_tokens=?,output_tokens=?,updated_at=?
                     WHERE id=?
                    """, settlement.estimatedUsd(), settlement.measuredAudioSeconds(), settlement.inputTokens(),
                    settlement.outputTokens(), OffsetDateTime.now(clock), requestId);
            return find(requestId);
        });
    }

    public LedgerEntry reconcile(UUID requestId, BigDecimal actualUsd, String source) {
        rejectActiveCallerTransaction();
        Objects.requireNonNull(requestId, "requestId");
        BigDecimal actual = boundedMoney(actualUsd, "actualUsd", false, false);
        String normalizedSource = text(source, "source", ACTUAL_SOURCE_MAX);
        return requiresNew.execute(status -> {
            lockSettings();
            LedgerEntry entry = findLocked(requestId);
            if (entry.state() != UsageState.SETTLED && entry.state() != UsageState.UNCERTAIN) {
                throw conflict("USAGE_CANNOT_BE_RECONCILED", "Only settled or uncertain usage can be reconciled");
            }
            if (entry.actualUsd() != null) {
                if (entry.actualUsd().compareTo(actual) == 0 && entry.actualSource().equals(normalizedSource)) return entry;
                throw conflict("RECONCILIATION_MISMATCH", "Request was already reconciled with different billing data");
            }
            jdbc.update("UPDATE ai_usage_records SET actual_usd=?,actual_source=?,updated_at=? WHERE id=?",
                    actual, normalizedSource, OffsetDateTime.now(clock), requestId);
            return find(requestId);
        });
    }

    public LedgerTotals totals(YearMonth month) {
        Objects.requireNonNull(month, "month");
        return accumulate(rowsForMonth(month));
    }

    public LedgerTotals totalsForMeeting(UUID meetingId) {
        Objects.requireNonNull(meetingId, "meetingId");
        return accumulate(jdbc.query("SELECT * FROM ai_usage_records WHERE meeting_id=?", this::mapEntry, meetingId));
    }

    public Map<UUID, LedgerTotals> byMeeting(YearMonth month) {
        Objects.requireNonNull(month, "month");
        Map<UUID, TotalsBuilder> builders = new HashMap<>();
        for (LedgerEntry entry : rowsForMonth(month)) {
            if (entry.meetingId() == null) continue;
            builders.computeIfAbsent(entry.meetingId(), ignored -> new TotalsBuilder()).add(entry);
        }
        Map<UUID, LedgerTotals> result = new HashMap<>();
        builders.forEach((meetingId, builder) -> result.put(meetingId, builder.build()));
        return Map.copyOf(result);
    }

    private LedgerEntry reserveInTransaction(UUID requestId, ReservationRequest request,
                                            BigDecimal maximumMeetingUsd, long maximumMeetingSeconds) {
        lockSettings();
        LedgerEntry existing = findOptional(requestId);
        if (existing != null) {
            if (sameReservation(existing, request)) return existing;
            throw conflict("RESERVATION_MISMATCH", "Request id was already used for a different reservation");
        }
        lockSubject(request.meetingId(), request.topicId());
        Integer maxMinutes = jdbc.queryForObject("SELECT per_meeting_max_minutes FROM app_settings WHERE id=1",
                Integer.class);
        if (maxMinutes == null || request.maximumAudioSeconds() > Math.multiplyExact(maxMinutes.longValue(), 60L)) {
            throw bad("AUDIO_LIMIT_EXCEEDED", "maximumAudioSeconds exceeds the per-meeting limit");
        }
        if (maximumMeetingUsd != null) {
            List<LedgerEntry> previous = jdbc.query("SELECT * FROM ai_usage_records WHERE meeting_id=?",
                    this::mapEntry, request.meetingId());
            if (accumulate(previous).budgetCommittedUsd().add(request.reservedUsd()).compareTo(maximumMeetingUsd) > 0) {
                throw conflict("MEETING_BUDGET_EXCEEDED", "Meeting AI budget is exhausted");
            }
            if ("TRANSCRIPTION".equals(request.operation())) {
                long used = 0;
                for (LedgerEntry entry : previous) {
                    if (!"TRANSCRIPTION".equals(entry.operation()) || entry.state() == UsageState.RELEASED) continue;
                    used = Math.addExact(used, entry.state() == UsageState.SETTLED && entry.measuredAudioSeconds() != null
                            ? entry.measuredAudioSeconds() : entry.maximumAudioSeconds());
                }
                long cap = Math.min(maximumMeetingSeconds, Math.multiplyExact(maxMinutes.longValue(), 60L));
                if (used > cap || request.maximumAudioSeconds() > cap - used) {
                    throw conflict("MEETING_AUDIO_LIMIT_EXCEEDED", "Meeting transcription time limit is exhausted");
                }
            }
        }
        YearMonth month = YearMonth.from(OffsetDateTime.now(clock));
        BigDecimal budget = monthlyBudget();
        if (budget.signum() == 0 || committed(month).add(request.reservedUsd()).compareTo(budget) > 0) {
            throw budgetExceeded();
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        jdbc.update("""
                INSERT INTO ai_usage_records(
                  id,meeting_id,provider,model,operation,price_version,state,reservation_month,reserved_usd,
                  maximum_audio_seconds,created_at,updated_at,topic_id)
                VALUES (?,?,?,?,?,?,'RESERVED',?,?,?,?,?,?)
                """, requestId, request.meetingId(), request.provider(), request.model(), request.operation(),
                request.priceVersion(), Date.valueOf(month.atDay(1)), request.reservedUsd(),
                request.maximumAudioSeconds(), now, now, request.topicId());
        return find(requestId);
    }

    private void lockSettings() {
        jdbc.queryForObject("SELECT id FROM app_settings WHERE id=1 FOR UPDATE", Integer.class);
    }

    private void lockMeeting(UUID meetingId) {
        List<UUID> rows = jdbc.query("SELECT id FROM meetings WHERE id=? FOR UPDATE",
                (rs, row) -> rs.getObject("id", UUID.class), meetingId);
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "MEETING_NOT_FOUND", "Meeting not found");
        }
    }

    private void lockSubject(UUID meetingId, UUID topicId) {
        if (meetingId != null) {
            lockMeeting(meetingId);
            return;
        }
        List<UUID> rows = jdbc.query("SELECT id FROM meeting_topics WHERE id=? FOR UPDATE",
                (rs, row) -> rs.getObject("id", UUID.class), topicId);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "TOPIC_NOT_FOUND", "Topic not found");
    }

    private BigDecimal monthlyBudget() {
        return money(jdbc.queryForObject("SELECT monthly_budget_usd FROM app_settings WHERE id=1", BigDecimal.class));
    }

    private BigDecimal committed(YearMonth month) {
        return accumulate(rowsForMonth(month)).budgetCommittedUsd();
    }

    private List<LedgerEntry> rowsForMonth(YearMonth month) {
        return jdbc.query("""
                SELECT id,meeting_id,provider,model,operation,price_version,state,reservation_month,
                       reserved_usd,estimated_usd,actual_usd,actual_source,maximum_audio_seconds,
                       measured_audio_seconds,input_tokens,output_tokens,created_at,updated_at,topic_id
                  FROM ai_usage_records WHERE reservation_month=? ORDER BY created_at,id
                """, this::mapEntry, Date.valueOf(month.atDay(1)));
    }

    private LedgerEntry findLocked(UUID id) {
        List<LedgerEntry> rows = jdbc.query("""
                SELECT id,meeting_id,provider,model,operation,price_version,state,reservation_month,
                       reserved_usd,estimated_usd,actual_usd,actual_source,maximum_audio_seconds,
                       measured_audio_seconds,input_tokens,output_tokens,created_at,updated_at,topic_id
                  FROM ai_usage_records WHERE id=? FOR UPDATE
                """, this::mapEntry, id);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "USAGE_REQUEST_NOT_FOUND", "Usage request not found");
        return rows.getFirst();
    }

    private LedgerEntry find(UUID id) {
        LedgerEntry entry = findOptional(id);
        if (entry == null) throw new ApiException(HttpStatus.NOT_FOUND, "USAGE_REQUEST_NOT_FOUND", "Usage request not found");
        return entry;
    }

    private LedgerEntry findOptional(UUID id) {
        List<LedgerEntry> rows = jdbc.query("""
                SELECT id,meeting_id,provider,model,operation,price_version,state,reservation_month,
                       reserved_usd,estimated_usd,actual_usd,actual_source,maximum_audio_seconds,
                       measured_audio_seconds,input_tokens,output_tokens,created_at,updated_at,topic_id
                  FROM ai_usage_records WHERE id=?
                """, this::mapEntry, id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private LedgerEntry mapEntry(ResultSet rs, int row) throws SQLException {
        return new LedgerEntry(
                rs.getObject("id", UUID.class), rs.getObject("meeting_id", UUID.class), rs.getString("provider"),
                rs.getString("model"), rs.getString("operation"), rs.getString("price_version"),
                UsageState.valueOf(rs.getString("state")), YearMonth.from(rs.getDate("reservation_month").toLocalDate()),
                money(rs.getBigDecimal("reserved_usd")), nullableMoney(rs.getBigDecimal("estimated_usd")),
                nullableMoney(rs.getBigDecimal("actual_usd")), rs.getString("actual_source"),
                rs.getLong("maximum_audio_seconds"), nullableLong(rs, "measured_audio_seconds"),
                nullableLong(rs, "input_tokens"), nullableLong(rs, "output_tokens"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class),
                rs.getObject("topic_id", UUID.class));
    }

    private void updateState(UUID id, UsageState state) {
        jdbc.update("UPDATE ai_usage_records SET state=?,updated_at=? WHERE id=?", state.name(), OffsetDateTime.now(clock), id);
    }

    private static void rejectActiveCallerTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Cost ledger mutations must be called outside an active transaction");
        }
    }

    private ReservationRequest validate(ReservationRequest request) {
        if (request == null || (request.meetingId() == null) == (request.topicId() == null)) {
            throw bad("INVALID_RESERVATION", "Exactly one meetingId or topicId is required");
        }
        if (request.topicId() != null && !"GLOSSARY".equals(request.operation())) {
            throw bad("INVALID_RESERVATION", "Topic reservations must generate glossary candidates");
        }
        if (request.maximumAudioSeconds() <= 0) throw bad("INVALID_RESERVATION", "maximumAudioSeconds must be positive");
        return new ReservationRequest(request.meetingId(), text(request.provider(), "provider", PROVIDER_MAX),
                text(request.model(), "model", MODEL_MAX), text(request.operation(), "operation", OPERATION_MAX),
                text(request.priceVersion(), "priceVersion", PRICE_VERSION_MAX),
                boundedMoney(request.reservedUsd(), "reservedUsd", true, true), request.maximumAudioSeconds(), request.topicId());
    }

    private Settlement validate(Settlement settlement) {
        if (settlement == null) throw bad("INVALID_SETTLEMENT", "Settlement is required");
        return new Settlement(boundedMoney(settlement.estimatedUsd(), "estimatedUsd", false, false),
                nonnegative(settlement.measuredAudioSeconds(), "measuredAudioSeconds"),
                nonnegative(settlement.inputTokens(), "inputTokens"), nonnegative(settlement.outputTokens(), "outputTokens"));
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Long nonnegative(Long value, String field) {
        if (value != null && value < 0) throw bad("INVALID_SETTLEMENT", field + " must be nonnegative");
        return value;
    }

    private static String text(String value, String field, int max) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > max) {
            throw bad("INVALID_USAGE_VALUE", field + " must contain 1 to " + max + " characters");
        }
        return normalized;
    }

    private static BigDecimal boundedMoney(BigDecimal value, String field, boolean positive, boolean roundUp) {
        if (value == null) throw bad("INVALID_USAGE_VALUE", field + " is required");
        BigDecimal normalized;
        try {
            normalized = value.setScale(MONEY_SCALE, roundUp ? RoundingMode.CEILING : RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw bad("INVALID_USAGE_VALUE", field + " must have at most six decimal places");
        }
        if ((positive ? normalized.signum() <= 0 : normalized.signum() < 0) || normalized.compareTo(MAX_MONEY) > 0) {
            throw bad("INVALID_USAGE_VALUE", field + " is outside the supported range");
        }
        return normalized;
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(MONEY_SCALE);
    }

    private static BigDecimal nullableMoney(BigDecimal value) {
        return value == null ? null : money(value);
    }

    private static boolean sameReservation(LedgerEntry entry, ReservationRequest request) {
        return Objects.equals(entry.meetingId(), request.meetingId()) && Objects.equals(entry.topicId(), request.topicId())
                && entry.provider().equals(request.provider())
                && entry.model().equals(request.model()) && entry.operation().equals(request.operation())
                && entry.priceVersion().equals(request.priceVersion())
                && entry.reservedUsd().compareTo(request.reservedUsd()) == 0
                && entry.maximumAudioSeconds() == request.maximumAudioSeconds();
    }

    private static boolean sameSettlement(LedgerEntry entry, Settlement settlement) {
        return entry.estimatedUsd().compareTo(settlement.estimatedUsd()) == 0
                && Objects.equals(entry.measuredAudioSeconds(), settlement.measuredAudioSeconds())
                && Objects.equals(entry.inputTokens(), settlement.inputTokens())
                && Objects.equals(entry.outputTokens(), settlement.outputTokens());
    }

    private static void requireState(LedgerEntry entry, UsageState state, String code) {
        if (entry.state() != state) throw conflict(code, "Usage request is in state " + entry.state());
    }

    private static LedgerTotals accumulate(List<LedgerEntry> rows) {
        TotalsBuilder builder = new TotalsBuilder();
        rows.forEach(builder::add);
        return builder.build();
    }

    private static ApiException budgetExceeded() {
        return new ApiException(HttpStatus.PAYMENT_REQUIRED, "MONTHLY_BUDGET_EXCEEDED", "Monthly paid API budget is exhausted");
    }

    private static ApiException bad(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    private static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    private static final class TotalsBuilder {
        private BigDecimal estimated = BigDecimal.ZERO.setScale(MONEY_SCALE);
        private BigDecimal actual = BigDecimal.ZERO.setScale(MONEY_SCALE);
        private BigDecimal reserved = BigDecimal.ZERO.setScale(MONEY_SCALE);
        private BigDecimal committed = BigDecimal.ZERO.setScale(MONEY_SCALE);
        private long unreconciled;
        private long requests;

        void add(LedgerEntry entry) {
            if (entry.state() == UsageState.RELEASED) return;
            requests++;
            if (entry.estimatedUsd() != null) estimated = estimated.add(entry.estimatedUsd());
            if (entry.actualUsd() == null) unreconciled++; else actual = actual.add(entry.actualUsd());
            if (entry.state() == UsageState.RESERVED || entry.state() == UsageState.DISPATCHED) {
                reserved = reserved.add(entry.reservedUsd());
                committed = committed.add(entry.reservedUsd());
            } else if (entry.state() == UsageState.UNCERTAIN) {
                reserved = reserved.add(entry.reservedUsd());
                BigDecimal billed = entry.actualUsd() == null ? BigDecimal.ZERO : entry.actualUsd();
                committed = committed.add(entry.reservedUsd().max(billed));
            } else {
                BigDecimal estimate = entry.estimatedUsd() == null ? BigDecimal.ZERO : entry.estimatedUsd();
                BigDecimal billed = entry.actualUsd() == null ? BigDecimal.ZERO : entry.actualUsd();
                committed = committed.add(estimate.max(billed));
            }
        }

        LedgerTotals build() {
            return new LedgerTotals(estimated, unreconciled == 0 ? actual : null, reserved, committed,
                    unreconciled, requests);
        }
    }
}
