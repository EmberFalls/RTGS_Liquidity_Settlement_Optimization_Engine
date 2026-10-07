package com.rtgs.settlement;

import com.rtgs.common.RtgsEngine;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/settlements")
public class SettlementController {
    private final RtgsEngine engine;
    public SettlementController(RtgsEngine engine) { this.engine = engine; }

    @GetMapping("/{paymentId}")
    public SettlementRecord get(@PathVariable String paymentId) {
        SettlementRecord record = engine.settlement(paymentId);
        if (record == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "settlement not found");
        return record;
    }
}
