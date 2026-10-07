package com.rtgs.redis;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Rebuildable, advisory state. Redis errors never change committed PostgreSQL facts. */
@Service
@Profile("redis")
public class RedisRecoveryService {
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private volatile boolean healthy = true;

    public RedisRecoveryService(JdbcTemplate jdbc, StringRedisTemplate redis) { this.jdbc = jdbc; this.redis = redis; }

    public boolean settledHint(String id) {
        try { return Boolean.TRUE.equals(redis.hasKey("rtgs:settled:" + id)); }
        catch (RuntimeException failure) { healthy = false; return false; }
    }

    public synchronized void refresh(Collection<String> participants, Collection<String> paymentIds) {
        try {
            for (String id : participants) {
                jdbc.query("SELECT available_minor FROM liquidity_positions WHERE participant_id=?", rs -> {
                    redis.opsForValue().set("rtgs:liquidity:" + id, Long.toString(rs.getLong(1)), Duration.ofMinutes(5));
                }, id);
            }
            for (String id : paymentIds) {
                Long count = jdbc.queryForObject("SELECT count(*) FROM settlements WHERE payment_id=?", Long.class, id);
                if (count != null && count > 0) redis.opsForValue().set("rtgs:settled:" + id, "1", Duration.ofHours(24));
            }
            rebuildQueue();
            healthy = true;
        } catch (RuntimeException failure) { healthy = false; }
    }

    public synchronized boolean rebuild() {
        try {
            List<String> participants = jdbc.queryForList("SELECT participant_id FROM liquidity_positions ORDER BY participant_id", String.class);
            List<String> settled = jdbc.queryForList("SELECT payment_id FROM settlements ORDER BY payment_id", String.class);
            refresh(participants, settled);
            return healthy;
        } catch (RuntimeException failure) { healthy = false; return false; }
    }

    private void rebuildQueue() {
        List<String> ids = jdbc.queryForList("""
                SELECT payment_id FROM payments WHERE status='QUEUED'
                ORDER BY CASE priority WHEN 'URGENT' THEN 0 WHEN 'HIGH' THEN 1 ELSE 2 END,
                  deadline NULLS LAST, queued_at, payment_id
                """, String.class);
        String temporary = "rtgs:queue:rebuilding";
        redis.delete(temporary);
        for (int i = 0; i < ids.size(); i++) redis.opsForZSet().add(temporary, ids.get(i), i);
        if (ids.isEmpty()) redis.delete("rtgs:queue");
        else redis.rename(temporary, "rtgs:queue");
    }

    public boolean healthy() { return healthy; }
}
