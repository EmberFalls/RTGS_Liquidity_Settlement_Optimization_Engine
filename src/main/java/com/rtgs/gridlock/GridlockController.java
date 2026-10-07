package com.rtgs.gridlock;

import com.rtgs.common.RtgsEngine;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/gridlock")
public class GridlockController {
    private final RtgsEngine engine;
    public GridlockController(RtgsEngine engine) { this.engine = engine; }
    @PostMapping("/run")
    public List<GridlockResolutionResult> run() {
        return engine.runGridlock();
    }
}
