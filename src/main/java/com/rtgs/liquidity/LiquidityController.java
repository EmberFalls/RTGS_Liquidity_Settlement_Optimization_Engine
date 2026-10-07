package com.rtgs.liquidity;

import com.rtgs.common.RtgsEngine;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/liquidity")
public class LiquidityController {
    private final RtgsEngine engine;
    public LiquidityController(RtgsEngine engine) { this.engine = engine; }

    @GetMapping("/{participantId}")
    public LiquidityPosition get(@PathVariable String participantId) { return engine.liquidity(participantId); }

    @PostMapping("/{participantId}/inject")
    public Map<String, Long> inject(@PathVariable String participantId, @RequestBody InjectionRequest request) {
        int settled = engine.injectAndRun(participantId, request.amountMinor());
        return Map.of("availableMinor", engine.liquidity(participantId).availableMinor(), "settledFromQueue", (long) settled);
    }

    public record InjectionRequest(long amountMinor) { }
}
