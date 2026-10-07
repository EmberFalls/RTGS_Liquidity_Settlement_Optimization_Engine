package com.rtgs.redis;

import static org.junit.jupiter.api.Assertions.*;

import com.github.fppt.jedismock.RedisServer;
import com.rtgs.common.RtgsEngine;
import com.rtgs.payment.*;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(properties = {
        "spring.profiles.active=postgres,redis",
        "spring.datasource.url=jdbc:postgresql://127.0.0.1:55432/rtgs",
        "spring.datasource.username=rtgs", "spring.datasource.password="
})
@EnabledIfSystemProperty(named = "rtgs.redis.test", matches = "true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RedisIntegrationTest {
    private static RedisServer server;
    @Autowired RtgsEngine engine;
    @Autowired RedisRecoveryService recovery;
    @Autowired StringRedisTemplate redis;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) throws IOException {
        server = RedisServer.newRedisServer().start();
        properties.add("spring.data.redis.host", server::getHost);
        properties.add("spring.data.redis.port", server::getBindPort);
    }

    @AfterAll static void stop() throws IOException { if (server != null) server.stop(); }

    @Test @Order(1) void cacheRebuildMatchesDurableStateAndDuplicateIsHarmless() {
        String prefix = UUID.randomUUID().toString().substring(0, 8);
        String a = prefix + "A", b = prefix + "B", settled = prefix + "P", queued = prefix + "Q";
        engine.register(a, "Bank A", 100);
        engine.register(b, "Bank B", 0);
        engine.submit(payment(settled, a, b, 40));
        engine.submit(payment(queued, a, b, 100));
        assertEquals("60", redis.opsForValue().get("rtgs:liquidity:" + a));
        assertTrue(recovery.settledHint(settled));
        assertTrue(redis.opsForZSet().range("rtgs:queue", 0, -1).contains(queued));
        redis.delete(redis.keys("rtgs:*"));
        assertTrue(recovery.rebuild());
        assertEquals("60", redis.opsForValue().get("rtgs:liquidity:" + a));
        assertTrue(recovery.settledHint(settled));
        engine.submit(payment(settled, a, b, 40));
        assertEquals(60, engine.liquidity(a).availableMinor());
        redis.opsForValue().set("rtgs:settled:" + queued, "1");
        assertEquals(PaymentStatus.QUEUED, engine.submit(payment(queued, a, b, 100)).status());
        assertNull(engine.settlement(queued));
    }

    @Test @Order(2) void redisOutageCannotCreateFalseSettlementOrLoseTruth() throws IOException {
        String prefix = UUID.randomUUID().toString().substring(0, 8);
        String a = prefix + "A", b = prefix + "B", id = prefix + "P";
        engine.register(a, "Bank A", 100);
        engine.register(b, "Bank B", 0);
        server.stop();
        server = null;
        engine.submit(payment(id, a, b, 40));
        engine.submit(payment(id, a, b, 40));
        assertEquals(60, engine.liquidity(a).availableMinor());
        assertEquals(40, engine.liquidity(b).availableMinor());
        assertNotNull(engine.settlement(id));
        assertFalse(recovery.healthy());
    }

    private static PaymentInstruction payment(String id, String source, String destination, long amount) {
        return PaymentInstruction.received(id, source, destination, amount, PaymentPriority.NORMAL,
                Instant.parse("2026-01-01T00:00:00Z"), null);
    }
}
