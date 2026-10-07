package com.rtgs.queue;

import com.rtgs.common.RtgsEngine;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/scheduler")
public class SchedulerController {
    private final RtgsEngine engine;
    public SchedulerController(RtgsEngine engine) { this.engine = engine; }
    @PostMapping("/run") public Map<String, Integer> run() { return Map.of("settled", engine.runScheduler()); }
}
