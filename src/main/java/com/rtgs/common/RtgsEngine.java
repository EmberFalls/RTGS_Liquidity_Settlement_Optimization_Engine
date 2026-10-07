package com.rtgs.common;

import com.rtgs.gridlock.GridlockResolutionResult;
import com.rtgs.liquidity.LiquidityPosition;
import com.rtgs.payment.Participant;
import com.rtgs.payment.PaymentInstruction;
import com.rtgs.settlement.SettlementRecord;
import java.util.List;

public interface RtgsEngine {
    Participant register(String participantId, String displayName, long openingLiquidityMinor);
    PaymentInstruction submit(PaymentInstruction payment);
    PaymentInstruction payment(String paymentId);
    List<PaymentInstruction> queued();
    LiquidityPosition liquidity(String participantId);
    int injectAndRun(String participantId, long amountMinor);
    int runScheduler();
    List<GridlockResolutionResult> runGridlock();
    SettlementRecord settlement(String paymentId);
}
