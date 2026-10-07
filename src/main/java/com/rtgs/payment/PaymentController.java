package com.rtgs.payment;

import com.rtgs.common.RtgsEngine;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class PaymentController {
    private final RtgsEngine engine;
    public PaymentController(RtgsEngine engine) { this.engine = engine; }

    @PostMapping("/participants")
    public Participant register(@RequestBody ParticipantRequest request) {
        return engine.register(request.participantId(), request.displayName(), request.openingLiquidityMinor());
    }

    @PostMapping("/payments")
    public PaymentInstruction submit(@RequestBody PaymentRequest request) {
        if (request.priority() == null) throw new IllegalArgumentException("priority is required");
        return engine.submit(PaymentInstruction.received(request.paymentId(), request.sourceParticipantId(),
                request.destinationParticipantId(), request.amountMinor(), request.priority(),
                request.createdAt() == null ? Instant.now() : request.createdAt(), request.deadline()));
    }

    @GetMapping("/payments/{paymentId}")
    public PaymentInstruction get(@PathVariable String paymentId) { return engine.payment(paymentId); }

    @GetMapping(value = "/payments", params = "status=QUEUED")
    public List<PaymentInstruction> queued() { return engine.queued(); }

    public record ParticipantRequest(String participantId, String displayName, long openingLiquidityMinor) { }
    public record PaymentRequest(String paymentId, String sourceParticipantId, String destinationParticipantId,
                                 long amountMinor, PaymentPriority priority, Instant createdAt, Instant deadline) { }
}
