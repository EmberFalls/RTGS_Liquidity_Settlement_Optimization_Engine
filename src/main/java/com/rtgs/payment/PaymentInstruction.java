package com.rtgs.payment;

import java.time.Instant;
import java.util.Objects;

public record PaymentInstruction(
        String paymentId,
        String sourceParticipantId,
        String destinationParticipantId,
        long amountMinor,
        PaymentPriority priority,
        Instant createdAt,
        Instant deadline,
        PaymentStatus status,
        Instant queuedAt,
        Instant settledAt) {

    public PaymentInstruction {
        if (paymentId == null || paymentId.isBlank()) throw new IllegalArgumentException("paymentId is required");
        if (sourceParticipantId == null || sourceParticipantId.isBlank()) throw new IllegalArgumentException("sourceParticipantId is required");
        if (destinationParticipantId == null || destinationParticipantId.isBlank()) throw new IllegalArgumentException("destinationParticipantId is required");
        if (sourceParticipantId.equals(destinationParticipantId)) throw new IllegalArgumentException("source and destination must differ");
        if (amountMinor <= 0) throw new IllegalArgumentException("amountMinor must be positive");
        Objects.requireNonNull(priority, "priority is required");
        Objects.requireNonNull(createdAt, "createdAt is required");
        Objects.requireNonNull(status, "status is required");
        if (deadline != null && deadline.isBefore(createdAt)) throw new IllegalArgumentException("deadline precedes creation");
        if (status == PaymentStatus.QUEUED && queuedAt == null) throw new IllegalArgumentException("queuedAt is required for queued payment");
        if (status == PaymentStatus.SETTLED && settledAt == null) throw new IllegalArgumentException("settledAt is required for settled payment");
        if (status != PaymentStatus.SETTLED && settledAt != null) throw new IllegalArgumentException("non-settled payment has settledAt");
    }

    public static PaymentInstruction received(String paymentId, String source, String destination,
                                               long amountMinor, PaymentPriority priority, Instant createdAt, Instant deadline) {
        return new PaymentInstruction(paymentId, source, destination, amountMinor, priority, createdAt,
                deadline, PaymentStatus.RECEIVED, null, null);
    }

    public PaymentInstruction queued(Instant at) {
        if (status != PaymentStatus.RECEIVED) throw new IllegalStateException("only received payments can queue");
        return new PaymentInstruction(paymentId, sourceParticipantId, destinationParticipantId, amountMinor,
                priority, createdAt, deadline, PaymentStatus.QUEUED, Objects.requireNonNull(at), null);
    }

    public PaymentInstruction settled(Instant at) {
        if (status != PaymentStatus.RECEIVED && status != PaymentStatus.QUEUED)
            throw new IllegalStateException("payment cannot settle from " + status);
        return new PaymentInstruction(paymentId, sourceParticipantId, destinationParticipantId, amountMinor,
                priority, createdAt, deadline, PaymentStatus.SETTLED, queuedAt, Objects.requireNonNull(at));
    }
}
