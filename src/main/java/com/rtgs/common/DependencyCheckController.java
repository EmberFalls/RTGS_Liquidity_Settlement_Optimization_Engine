package com.rtgs.common;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.kafka.clients.admin.AdminClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("kafka & redis & postgres")
public class DependencyCheckController {
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final String brokers;
    public DependencyCheckController(JdbcTemplate jdbc, StringRedisTemplate redis,
                                     @Value("${spring.kafka.bootstrap-servers}") String brokers) {
        this.jdbc = jdbc; this.redis = redis; this.brokers = brokers;
    }
    @GetMapping("/api/admin/dependencies")
    public Map<String, Boolean> dependencies() {
        Map<String, Boolean> result = new LinkedHashMap<>();
        try { result.put("postgres", jdbc.queryForObject("SELECT 1", Integer.class) == 1); }
        catch (RuntimeException e) { result.put("postgres", false); }
        try { result.put("redis", "PONG".equals(redis.execute((org.springframework.data.redis.core.RedisCallback<String>) connection -> connection.ping()))); }
        catch (RuntimeException e) { result.put("redis", false); }
        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", brokers, "request.timeout.ms", "3000"))) {
            admin.describeCluster().clusterId().get(5, java.util.concurrent.TimeUnit.SECONDS);
            result.put("kafka", true);
        } catch (Exception e) { result.put("kafka", false); }
        return result;
    }
}
