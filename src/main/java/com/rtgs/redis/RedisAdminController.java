package com.rtgs.redis;

import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("redis")
@RequestMapping("/api/admin/redis")
public class RedisAdminController {
    private final RedisRecoveryService recovery;
    public RedisAdminController(RedisRecoveryService recovery) { this.recovery = recovery; }
    @PostMapping("/rebuild") public Map<String, Boolean> rebuild() { return Map.of("rebuilt", recovery.rebuild()); }
}
