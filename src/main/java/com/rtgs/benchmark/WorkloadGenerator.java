package com.rtgs.benchmark;

import com.rtgs.payment.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class WorkloadGenerator {
    public Workload generate(WorkloadConfig config) {
        Random random = new Random(config.seed());
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        List<Workload.OpeningParticipant> participants = new ArrayList<>();
        for (int i = 0; i < config.participantCount(); i++)
            participants.add(new Workload.OpeningParticipant("BANK_" + i, config.openingLiquidityMinor()));
        List<Workload.ScheduledPayment> attempts = new ArrayList<>();
        List<PaymentInstruction> unique = new ArrayList<>();
        for (int i = 0; i < config.paymentCount(); i++) {
            long offset = (long) (i / config.burstSize()) * config.burstSize() * 1_000_000_000L / config.targetRatePerSecond();
            PaymentInstruction payment;
            if (!unique.isEmpty() && random.nextDouble() < config.duplicateRate()) {
                payment = unique.get(random.nextInt(unique.size()));
            } else {
                int source = "gridlock-heavy".equals(config.profile()) ? i % config.participantCount() : random.nextInt(config.participantCount());
                int destination = "gridlock-heavy".equals(config.profile()) ? (source + 1) % config.participantCount()
                        : (source + 1 + random.nextInt(config.participantCount() - 1)) % config.participantCount();
                long amount = config.minimumAmountMinor();
                if (config.maximumAmountMinor() > amount) {
                    if (config.amountDistribution() == WorkloadConfig.AmountDistribution.BIMODAL)
                        amount = random.nextDouble() < .8 ? config.minimumAmountMinor() : config.maximumAmountMinor();
                    else amount = random.nextLong(config.minimumAmountMinor(), Math.addExact(config.maximumAmountMinor(), 1));
                }
                double priority = random.nextDouble();
                PaymentPriority paymentPriority = priority < config.urgentRate() ? PaymentPriority.URGENT
                        : priority < config.urgentRate() + config.highRate() ? PaymentPriority.HIGH : PaymentPriority.NORMAL;
                Instant created = start.plusNanos(offset);
                Instant deadline = random.nextDouble() < config.deadlineRate() ? created.plusSeconds(60) : null;
                payment = PaymentInstruction.received("P_" + config.seed() + "_" + i, "BANK_" + source,
                        "BANK_" + destination, amount, paymentPriority, created, deadline);
                unique.add(payment);
            }
            attempts.add(new Workload.ScheduledPayment(offset, payment));
        }
        return new Workload(config, participants, attempts);
    }
}
