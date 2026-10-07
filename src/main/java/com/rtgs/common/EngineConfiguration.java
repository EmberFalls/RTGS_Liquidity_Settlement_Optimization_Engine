package com.rtgs.common;

import com.rtgs.concurrency.ParticipantLockManager;
import com.rtgs.liquidity.LiquidityService;
import com.rtgs.payment.ParticipantRegistry;
import com.rtgs.payment.PaymentService;
import com.rtgs.queue.QueueScheduler;
import com.rtgs.queue.SettlementQueue;
import com.rtgs.gridlock.GridlockResolver;
import com.rtgs.settlement.SettlementCoordinator;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("!postgres")
public class EngineConfiguration {
    @Bean ParticipantRegistry participantRegistry() { return new ParticipantRegistry(); }
    @Bean ParticipantLockManager participantLockManager() { return new ParticipantLockManager(); }
    @Bean PaymentService paymentService(ParticipantRegistry registry) { return new PaymentService(registry); }
    @Bean LiquidityService liquidityService(ParticipantRegistry registry, ParticipantLockManager locks) {
        return new LiquidityService(registry, locks);
    }
    @Bean Clock clock() { return Clock.systemUTC(); }
    @Bean SettlementCoordinator settlementCoordinator(PaymentService payments, LiquidityService liquidity,
                                                      Clock clock, ParticipantLockManager locks) {
        return new SettlementCoordinator(payments, liquidity, clock, locks);
    }
    @Bean SettlementQueue settlementQueue(PaymentService payments) { return new SettlementQueue(payments); }
    @Bean QueueScheduler queueScheduler(SettlementQueue queue, SettlementCoordinator coordinator, LiquidityService liquidity) {
        return new QueueScheduler(queue, coordinator, liquidity);
    }
    @Bean GridlockResolver gridlockResolver(PaymentService payments, LiquidityService liquidity,
                                            ParticipantLockManager locks, Clock clock, SettlementCoordinator coordinator) {
        return new GridlockResolver(payments, liquidity, locks, clock, coordinator.settlementStore());
    }
}
