package com.rtgs.persistence;

import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("postgres")
public class StatsController {
    private final JdbcTemplate jdbc;
    public StatsController(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @GetMapping("/api/stats") public Map<String, Object> stats() {
        return Map.of("payments", jdbc.queryForObject("SELECT count(*) FROM payments", Long.class),
                "settlements", jdbc.queryForObject("SELECT count(*) FROM settlements", Long.class),
                "queueDepth", jdbc.queryForObject("SELECT count(*) FROM payments WHERE status='QUEUED'", Long.class),
                "failures", jdbc.queryForObject("SELECT count(*) FROM processing_failures", Long.class));
    }
}
