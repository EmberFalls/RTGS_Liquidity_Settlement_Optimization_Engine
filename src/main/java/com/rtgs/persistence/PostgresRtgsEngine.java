package com.rtgs.persistence;

import com.rtgs.common.RtgsEngine;
import com.rtgs.concurrency.ParticipantLockManager;
import com.rtgs.gridlock.*;
import com.rtgs.liquidity.LiquidityPosition;
import com.rtgs.payment.*;
import com.rtgs.queue.QueuedPaymentComparator;
import com.rtgs.settlement.*;
import com.rtgs.redis.RedisRecoveryService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL is the source of truth in the postgres profile. Every financial operation has one DB transaction. */
@Service
@Profile("postgres")
public class PostgresRtgsEngine implements RtgsEngine {
    private static final RowMapper<PaymentInstruction> PAYMENT_MAPPER = (rs, ignored) -> new PaymentInstruction(
            rs.getString("payment_id"), rs.getString("source_participant_id"), rs.getString("destination_participant_id"),
            rs.getLong("amount_minor"), PaymentPriority.valueOf(rs.getString("priority")),
            instant(rs, "created_at"), instant(rs, "deadline"), PaymentStatus.valueOf(rs.getString("status")),
            instant(rs, "queued_at"), instant(rs, "settled_at"));
    private static final RowMapper<SettlementRecord> SETTLEMENT_MAPPER = (rs, ignored) -> new SettlementRecord(
            rs.getObject("settlement_id", UUID.class), rs.getString("payment_id"),
            rs.getString("source_participant_id"), rs.getString("destination_participant_id"),
            rs.getLong("amount_minor"), SettlementType.valueOf(rs.getString("settlement_type")),
            instant(rs, "settled_at"), rs.getObject("batch_id", UUID.class));

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ParticipantLockManager locks = new ParticipantLockManager();
    private final Clock clock;
    private final ObjectProvider<RedisRecoveryService> cache;
    private final GridlockDetector detector = new GridlockDetector();
    private final ProjectedLiquidityCalculator calculator = new ProjectedLiquidityCalculator();

    public PostgresRtgsEngine(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock,
                              ObjectProvider<RedisRecoveryService> cache) {
        this.jdbc = jdbc; this.transactions = transactions; this.clock = clock; this.cache = cache;
    }

    @Override public Participant register(String id, String name, long opening) {
        Participant participant = new Participant(id, name);
        new LiquidityPosition(id, opening);
        Participant registered = transactions.execute(status -> {
            jdbc.update("INSERT INTO participants(participant_id, display_name) VALUES (?, ?)", id, name);
            jdbc.update("INSERT INTO liquidity_positions(participant_id, available_minor) VALUES (?, ?)", id, opening);
            return participant;
        });
        refresh(List.of(id), List.of());
        return registered;
    }

    @Override public PaymentInstruction submit(PaymentInstruction instruction) {
        if (instruction.status() != PaymentStatus.RECEIVED) throw new IllegalArgumentException("new payment must be RECEIVED");
        RedisRecoveryService hints = cache.getIfAvailable();
        if (hints != null && hints.settledHint(instruction.paymentId())) {
            List<PaymentInstruction> found = jdbc.query("SELECT * FROM payments WHERE payment_id=?", PAYMENT_MAPPER, instruction.paymentId());
            if (!found.isEmpty()) {
                PaymentInstruction durable = found.getFirst();
                if (!sameInstruction(durable, instruction)) throw new IllegalArgumentException("paymentId reused with different instruction");
                if (durable.status() == PaymentStatus.SETTLED) return durable;
            }
        }
        PaymentInstruction result = locks.withLocks(List.of(instruction.sourceParticipantId(), instruction.destinationParticipantId()),
                () -> transactions.execute(status -> {
                    jdbc.update("""
                            INSERT INTO payments(payment_id, source_participant_id, destination_participant_id,
                              amount_minor, priority, status, created_at, deadline)
                            VALUES (?, ?, ?, ?, ?, 'RECEIVED', ?, ?)
                            ON CONFLICT (payment_id) DO NOTHING
                            """, instruction.paymentId(), instruction.sourceParticipantId(), instruction.destinationParticipantId(),
                            instruction.amountMinor(), instruction.priority().name(), Timestamp.from(instruction.createdAt()),
                            timestamp(instruction.deadline()));
                    PaymentInstruction stored = paymentForUpdate(instruction.paymentId());
                    if (!sameInstruction(stored, instruction)) throw new IllegalArgumentException("paymentId reused with different instruction");
                    return settleOne(stored);
                }));
        refresh(List.of(instruction.sourceParticipantId(), instruction.destinationParticipantId()), List.of(instruction.paymentId()));
        if (result.status() == PaymentStatus.SETTLED) runScheduler();
        return result;
    }

    private boolean sameInstruction(PaymentInstruction a, PaymentInstruction b) {
        return a.sourceParticipantId().equals(b.sourceParticipantId())
                && a.destinationParticipantId().equals(b.destinationParticipantId())
                && a.amountMinor() == b.amountMinor() && a.priority() == b.priority()
                && Objects.equals(a.deadline(), b.deadline());
    }

    private PaymentInstruction settleOne(PaymentInstruction payment) {
        if (payment.status() != PaymentStatus.RECEIVED && payment.status() != PaymentStatus.QUEUED) return payment;
        Map<String, Long> balances = lockedBalances(List.of(payment.sourceParticipantId(), payment.destinationParticipantId()));
        long source = balances.get(payment.sourceParticipantId());
        long destination = balances.get(payment.destinationParticipantId());
        Instant now = clock.instant();
        if (source < payment.amountMinor()) {
            if (payment.status() == PaymentStatus.QUEUED) return payment;
            jdbc.update("UPDATE payments SET status='QUEUED', queued_at=?, version=version+1 WHERE payment_id=?",
                    Timestamp.from(now), payment.paymentId());
            return payment.queued(now);
        }
        long credited = Math.addExact(destination, payment.amountMinor());
        jdbc.update("UPDATE liquidity_positions SET available_minor=?, version=version+1, updated_at=? WHERE participant_id=?",
                source - payment.amountMinor(), Timestamp.from(now), payment.sourceParticipantId());
        jdbc.update("UPDATE liquidity_positions SET available_minor=?, version=version+1, updated_at=? WHERE participant_id=?",
                credited, Timestamp.from(now), payment.destinationParticipantId());
        UUID settlementId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO settlements(settlement_id, payment_id, source_participant_id, destination_participant_id,
                  amount_minor, settlement_type, settled_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, settlementId, payment.paymentId(), payment.sourceParticipantId(), payment.destinationParticipantId(),
                payment.amountMinor(), payment.status() == PaymentStatus.QUEUED ? "QUEUED" : "IMMEDIATE", Timestamp.from(now));
        jdbc.update("UPDATE payments SET status='SETTLED', settled_at=?, version=version+1 WHERE payment_id=?",
                Timestamp.from(now), payment.paymentId());
        return payment.settled(now);
    }

    @Override public PaymentInstruction payment(String id) {
        return jdbc.query("SELECT * FROM payments WHERE payment_id=?", PAYMENT_MAPPER, id).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown payment: " + id));
    }

    private PaymentInstruction paymentForUpdate(String id) {
        return jdbc.query("SELECT * FROM payments WHERE payment_id=? FOR UPDATE", PAYMENT_MAPPER, id).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown payment: " + id));
    }

    @Override public List<PaymentInstruction> queued() {
        return jdbc.query("SELECT * FROM payments WHERE status='QUEUED'", PAYMENT_MAPPER).stream()
                .sorted(QueuedPaymentComparator.INSTANCE).toList();
    }

    @Override public LiquidityPosition liquidity(String id) {
        return jdbc.query("SELECT participant_id, available_minor FROM liquidity_positions WHERE participant_id=?",
                (rs, ignored) -> new LiquidityPosition(rs.getString(1), rs.getLong(2)), id).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown participant: " + id));
    }

    private Map<String, Long> lockedBalances(Collection<String> ids) {
        Map<String, Long> balances = new TreeMap<>();
        for (String id : new TreeSet<>(ids)) {
            List<Long> rows = jdbc.query("SELECT available_minor FROM liquidity_positions WHERE participant_id=? FOR UPDATE",
                    (rs, ignored) -> rs.getLong(1), id);
            if (rows.isEmpty()) throw new IllegalArgumentException("unknown participant: " + id);
            balances.put(id, rows.getFirst());
        }
        return balances;
    }

    @Override public int injectAndRun(String id, long amount) {
        if (amount <= 0) throw new IllegalArgumentException("injection must be positive");
        locks.withLocks(List.of(id), () -> transactions.execute(status -> {
            long current = lockedBalances(List.of(id)).get(id);
            jdbc.update("UPDATE liquidity_positions SET available_minor=?, version=version+1, updated_at=? WHERE participant_id=?",
                    Math.addExact(current, amount), Timestamp.from(clock.instant()), id);
            return null;
        }));
        refresh(List.of(id), List.of());
        return runScheduler();
    }

    @Override public synchronized int runScheduler() {
        int settled = 0;
        boolean progress;
        do {
            progress = false;
            for (PaymentInstruction queued : queued()) {
                PaymentInstruction result = locks.withLocks(List.of(queued.sourceParticipantId(), queued.destinationParticipantId()),
                        () -> transactions.execute(status -> settleOne(paymentForUpdate(queued.paymentId()))));
                refresh(List.of(queued.sourceParticipantId(), queued.destinationParticipantId()), List.of(queued.paymentId()));
                if (result.status() == PaymentStatus.SETTLED) {
                    settled++; progress = true; break;
                }
            }
        } while (progress);
        return settled;
    }

    @Override public List<GridlockResolutionResult> runGridlock() {
        List<GridlockResolutionResult> results = detector.detect(queued()).stream().map(this::resolveCandidate).toList();
        if (results.stream().anyMatch(GridlockResolutionResult::committed)) runScheduler();
        return results;
    }

    private GridlockResolutionResult resolveCandidate(GridlockCandidate candidate) {
        GridlockResolutionResult result = locks.withLocks(candidate.participants(), () -> transactions.execute(status -> resolveLocked(candidate)));
        if (result.committed()) refresh(candidate.participants(), result.settledPaymentIds());
        return result;
    }

    private void refresh(Collection<String> participants, Collection<String> payments) {
        RedisRecoveryService service = cache.getIfAvailable();
        if (service != null) service.refresh(participants, payments);
    }

    private GridlockResolutionResult resolveLocked(GridlockCandidate candidate) {
        List<PaymentInstruction> remaining = new ArrayList<>();
        for (PaymentEdge edge : candidate.edges()) {
            PaymentInstruction current = paymentForUpdate(edge.paymentId());
            if (current.status() != PaymentStatus.QUEUED || !current.sourceParticipantId().equals(edge.sourceParticipantId())
                    || !current.destinationParticipantId().equals(edge.destinationParticipantId())
                    || current.amountMinor() != edge.amountMinor())
                return rejected(List.of(), "candidate changed since detection");
            remaining.add(current);
        }
        Map<String, Long> balances = lockedBalances(candidate.participants());
        List<String> pruned = new ArrayList<>();
        while (remaining.size() >= 2) {
            List<GridlockCandidate> cycles = detector.detect(remaining);
            if (cycles.isEmpty()) return rejected(pruned, "no cycle remains");
            Set<String> ids = new HashSet<>();
            cycles.getFirst().edges().forEach(edge -> ids.add(edge.paymentId()));
            List<PaymentInstruction> batch = remaining.stream().filter(p -> ids.contains(p.paymentId())).toList();
            Map<String, Long> projected = calculator.calculate(batch, balances::get);
            if (calculator.feasible(projected)) return commitBatch(batch, projected, pruned);
            PaymentInstruction remove = batch.stream().max(Comparator
                    .comparingInt((PaymentInstruction p) -> p.priority().ordinal())
                    .thenComparingLong(PaymentInstruction::amountMinor)
                    .thenComparing(PaymentInstruction::paymentId)).orElseThrow();
            remaining.remove(remove); pruned.add(remove.paymentId());
        }
        return rejected(pruned, "no feasible cycle remains");
    }

    private GridlockResolutionResult commitBatch(List<PaymentInstruction> batch, Map<String, Long> projected,
                                                  List<String> pruned) {
        Instant now = clock.instant();
        UUID batchId = UUID.randomUUID();
        for (var entry : projected.entrySet()) {
            jdbc.update("UPDATE liquidity_positions SET available_minor=?, version=version+1, updated_at=? WHERE participant_id=?",
                    entry.getValue(), Timestamp.from(now), entry.getKey());
        }
        for (PaymentInstruction payment : batch) {
            jdbc.update("""
                    INSERT INTO settlements(settlement_id, payment_id, source_participant_id, destination_participant_id,
                      amount_minor, settlement_type, batch_id, settled_at)
                    VALUES (?, ?, ?, ?, ?, 'GRIDLOCK_BATCH', ?, ?)
                    """, UUID.randomUUID(), payment.paymentId(), payment.sourceParticipantId(),
                    payment.destinationParticipantId(), payment.amountMinor(), batchId, Timestamp.from(now));
            jdbc.update("UPDATE payments SET status='SETTLED', settled_at=?, version=version+1 WHERE payment_id=?",
                    Timestamp.from(now), payment.paymentId());
        }
        return new GridlockResolutionResult(true, batch.stream().map(PaymentInstruction::paymentId).toList(),
                pruned, batchId, "committed");
    }

    private GridlockResolutionResult rejected(List<String> pruned, String reason) {
        return new GridlockResolutionResult(false, List.of(), pruned, null, reason);
    }

    @Override public SettlementRecord settlement(String paymentId) {
        return jdbc.query("SELECT * FROM settlements WHERE payment_id=?", SETTLEMENT_MAPPER, paymentId).stream()
                .findFirst().orElse(null);
    }

    private static Timestamp timestamp(Instant instant) { return instant == null ? null : Timestamp.from(instant); }
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
