package com.rtgs.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.rtgs.payment.*;
import com.rtgs.redis.RedisRecoveryService;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {"spring.profiles.active=postgres", "spring.datasource.url=jdbc:postgresql://127.0.0.1:55432/rtgs",
        "spring.datasource.username=rtgs", "spring.datasource.password="})
@EnabledIfSystemProperty(named="rtgs.postgres.test", matches="true")
class PostgresConcurrencyTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;
    @Autowired ApplicationContext context;

    @Test void independentJavaLocksStillRespectDurableFinancialBoundary() throws Exception {
        PostgresRtgsEngine first = new PostgresRtgsEngine(jdbc, transactions, Clock.systemUTC(), context.getBeanProvider(RedisRecoveryService.class));
        PostgresRtgsEngine second = new PostgresRtgsEngine(jdbc, transactions, Clock.systemUTC(), context.getBeanProvider(RedisRecoveryService.class));
        String prefix = UUID.randomUUID().toString().substring(0, 8);
        String a=prefix+"A", b=prefix+"B";
        first.register(a,"Bank A",100); first.register(b,"Bank B",0);
        ExecutorService pool=Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> futures=new ArrayList<>();
            for(int i=0;i<20;i++) {
                int number=i;
                futures.add(pool.submit(() -> (number%2==0?first:second).submit(PaymentInstruction.received(
                        prefix+"p"+(number/2),a,b,30,PaymentPriority.NORMAL,Instant.parse("2026-01-01T00:00:00Z"),null))));
            }
            for(Future<?> future:futures) future.get(20,TimeUnit.SECONDS);
            assertEquals(10,first.liquidity(a).availableMinor());
            assertEquals(90,first.liquidity(b).availableMinor());
            assertEquals(3L,jdbc.queryForObject("SELECT count(*) FROM settlements WHERE source_participant_id=?",Long.class,a));
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS)); }
    }
}
