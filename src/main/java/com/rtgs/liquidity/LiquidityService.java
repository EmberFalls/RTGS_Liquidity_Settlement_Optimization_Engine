package com.rtgs.liquidity;

import com.rtgs.concurrency.ParticipantLockManager;
import com.rtgs.payment.ParticipantRegistry;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class LiquidityService {
    private final ParticipantRegistry registry;
    private final ParticipantLockManager locks;
    private final Map<String, LiquidityPosition> positions = new ConcurrentHashMap<>();

    public LiquidityService(ParticipantRegistry registry) { this(registry, new ParticipantLockManager()); }

    public LiquidityService(ParticipantRegistry registry, ParticipantLockManager locks) {
        this.registry = registry;
        this.locks = locks;
    }

    public void open(String participantId, long availableMinor) {
        registry.require(participantId);
        LiquidityPosition opening = new LiquidityPosition(participantId, availableMinor);
        if (positions.putIfAbsent(participantId, opening) != null)
            throw new IllegalArgumentException("liquidity already opened: " + participantId);
    }

    public long available(String participantId) { return require(participantId).availableMinor(); }

    public LiquidityPosition require(String participantId) {
        LiquidityPosition position = positions.get(participantId);
        if (position == null) throw new IllegalArgumentException("liquidity not opened: " + participantId);
        return position;
    }

    public void transfer(String source, String destination, long amountMinor) {
        if (amountMinor <= 0) throw new IllegalArgumentException("amount must be positive");
        long sourceBalance = available(source);
        long destinationBalance = available(destination);
        if (sourceBalance < amountMinor) throw new IllegalStateException("insufficient liquidity");
        long credited = Math.addExact(destinationBalance, amountMinor);
        positions.put(source, new LiquidityPosition(source, sourceBalance - amountMinor));
        positions.put(destination, new LiquidityPosition(destination, credited));
    }

    public long inject(String participantId, long amountMinor) {
        if (amountMinor <= 0) throw new IllegalArgumentException("injection must be positive");
        return locks.withLocks(java.util.List.of(participantId), () -> {
            long updated = Math.addExact(available(participantId), amountMinor);
            positions.put(participantId, new LiquidityPosition(participantId, updated));
            return updated;
        });
    }

    /** Caller holds the locks for every key and has checked the complete projection. */
    public void applyProjected(Map<String, Long> projected) {
        projected.forEach((id, amount) -> {
            require(id);
            if (amount < 0) throw new IllegalArgumentException("negative projected liquidity");
        });
        projected.forEach((id, amount) -> positions.put(id, new LiquidityPosition(id, amount)));
    }
}
