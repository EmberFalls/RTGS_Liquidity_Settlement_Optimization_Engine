package com.rtgs.concurrency;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

public class ParticipantLockManager {
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public <T> T withLocks(Collection<String> participantIds, Supplier<T> work) {
        List<ReentrantLock> acquired = participantIds.stream().distinct().sorted()
                .map(id -> locks.computeIfAbsent(id, ignored -> new ReentrantLock()))
                .toList();
        acquired.forEach(ReentrantLock::lock);
        try { return work.get(); }
        finally {
            for (int i = acquired.size() - 1; i >= 0; i--) acquired.get(i).unlock();
        }
    }
}
