package com.rtgs.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtgs.payment.PaymentInstruction;
import io.lettuce.core.RedisClient;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

public class EndToEndBenchmarkMain {
    public static void main(String[] args) throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        String baseUrl = args.length > 0 ? args[0] : "http://localhost:8080";
        int count = args.length > 1 ? Integer.parseInt(args[1]) : 1000;
        Path output = Path.of(args.length > 2 ? args[2] : "docs/benchmark-results/end-to-end.json");
        Workload workload = new WorkloadGenerator().generate(WorkloadConfig.profile("normal-day", count, 42));
        String prefix = "bench-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        ExecutorService executor = Executors.newFixedThreadPool(16);
        Map<String, Instant> sentAt = new HashMap<>();
        List<CompletableFuture<?>> requests = new ArrayList<>();
        AtomicInteger failures = new AtomicInteger();
        long start;
        Instant ingestionFinished;
        try (HttpClient http = HttpClient.newBuilder().executor(executor).connectTimeout(Duration.ofSeconds(5)).build()) {
            for (var participant : workload.participants()) {
                String body = json.writeValueAsString(Map.of("participantId", prefix + participant.participantId(),
                        "displayName", "Benchmark " + participant.participantId(), "openingLiquidityMinor", participant.openingLiquidityMinor()));
                HttpResponse<String> response = http.send(request(baseUrl + "/api/participants", body), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) throw new IllegalStateException(response.body());
            }
            start = System.nanoTime();
            for (var attempt : workload.payments()) {
                long remaining = start + attempt.offsetNanos() - System.nanoTime();
                if (remaining > 0) LockSupport.parkNanos(remaining);
                PaymentInstruction p = attempt.payment();
                PaymentInstruction translated = PaymentInstruction.received(prefix + p.paymentId(), prefix + p.sourceParticipantId(),
                        prefix + p.destinationParticipantId(), p.amountMinor(), p.priority(), p.createdAt(), p.deadline());
                sentAt.putIfAbsent(translated.paymentId(), Instant.now());
                requests.add(http.sendAsync(request(baseUrl + "/api/payments", json.writeValueAsString(translated)),
                        HttpResponse.BodyHandlers.ofString()).handle((response, error) -> {
                            if (error != null || response.statusCode() != 200) failures.incrementAndGet();
                            return null;
                        }));
            }
            CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new)).get(2, TimeUnit.MINUTES);
            ingestionFinished = Instant.now();
        } finally {
            executor.shutdownNow();
        }

        String dbUrl = System.getenv().getOrDefault("RTGS_BENCH_DB_URL", "jdbc:postgresql://localhost:15432/rtgs");
        String dbUser = System.getenv().getOrDefault("RTGS_BENCH_DB_USER", "rtgs");
        String dbPassword = System.getenv().getOrDefault("RTGS_BENCH_DB_PASSWORD", "rtgs");
        Map<String, Long> latencies = new HashMap<>();
        Instant latestSettlement = ingestionFinished;
        int queued = 0, durablePayments = 0;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        long financialDuplicates;
        boolean nonnegative;
        long finalLiquidity;
        try (Connection connection = DriverManager.getConnection(dbUrl, dbUser, dbPassword)) {
            do {
                queued = 0; durablePayments = 0;
                try (PreparedStatement statement = connection.prepareStatement("SELECT payment_id,status,settled_at FROM payments WHERE payment_id LIKE ?")) {
                    statement.setString(1, prefix + "%");
                    try (ResultSet rows = statement.executeQuery()) {
                        while (rows.next()) {
                            durablePayments++;
                            if ("QUEUED".equals(rows.getString(2))) queued++;
                            Timestamp settled = rows.getTimestamp(3);
                            if (settled != null) {
                                Instant at = settled.toInstant();
                                latencies.putIfAbsent(rows.getString(1), Math.max(0, Duration.between(sentAt.get(rows.getString(1)), at).toNanos()));
                                if (at.isAfter(latestSettlement)) latestSettlement = at;
                            }
                        }
                    }
                }
                if (latencies.size() == sentAt.size()) break;
                Thread.sleep(100);
            } while (System.nanoTime() < deadline);
            financialDuplicates = queryLong(connection, "SELECT count(*) FROM (SELECT payment_id FROM settlements WHERE payment_id LIKE ? GROUP BY payment_id HAVING count(*)>1) d", prefix);
            long negative = queryLong(connection, "SELECT count(*) FROM liquidity_positions WHERE participant_id LIKE ? AND available_minor<0", prefix);
            nonnegative = negative == 0;
            finalLiquidity = queryLong(connection, "SELECT coalesce(sum(available_minor),0) FROM liquidity_positions WHERE participant_id LIKE ?", prefix);
        }
        long[] ordered = latencies.values().stream().mapToLong(Long::longValue).sorted().toArray();
        double duration = (System.nanoTime() - start) / 1_000_000_000.0;
        boolean redisHint;
        RedisClient redis = RedisClient.create(System.getenv().getOrDefault("RTGS_BENCH_REDIS_URI", "redis://localhost:16379"));
        try (var connection = redis.connect()) {
            redisHint = "1".equals(connection.sync().get("rtgs:settled:" + sentAt.keySet().iterator().next()));
        } finally { redis.shutdown(); }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("runPrefix", prefix); report.put("workload", workload.config());
        report.put("dataClassification", "MODELED ASSUMPTION");
        report.put("attempts", count); report.put("uniquePayments", sentAt.size()); report.put("durablePayments", durablePayments);
        report.put("settledPayments", latencies.size()); report.put("paymentsPerSecond", count / duration);
        report.put("settlementsPerSecond", latencies.size() / duration);
        report.put("p50LatencyMillis", percentile(ordered, .50)); report.put("p95LatencyMillis", percentile(ordered, .95));
        report.put("p99LatencyMillis", percentile(ordered, .99)); report.put("queueDepth", queued);
        report.put("failures", failures.get()); report.put("duplicateFinancialEffects", financialDuplicates);
        report.put("totalDurationSeconds", duration);
        report.put("backlogRecoveryTimeSeconds", Math.max(0, Duration.between(ingestionFinished, latestSettlement).toNanos() / 1_000_000_000.0));
        report.put("backlogRecoveryDefinition", "time from final broker-accepted submission to final durable settlement; consumer restart recovery is a separate failure test");
        report.put("nonnegativeLiquidity", nonnegative); report.put("liquidityConserved", finalLiquidity == workload.config().openingLiquidityMinor() * workload.config().participantCount());
        report.put("redisSettledHintPresent", redisHint);
        report.put("latencyDefinition", "client send wall-clock time to PostgreSQL settled_at on this same-host Docker environment");
        report.put("javaVersion", System.getProperty("java.version")); report.put("os", System.getProperty("os.name"));
        report.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        Files.createDirectories(output.toAbsolutePath().getParent());
        json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        if (failures.get() != 0 || latencies.size() != sentAt.size() || financialDuplicates != 0 || !nonnegative || !redisHint)
            throw new IllegalStateException("end-to-end benchmark correctness gate failed");
    }

    private static HttpRequest request(String url, String body) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }
    private static long queryLong(Connection connection, String sql, String prefix) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, prefix + "%");
            try (ResultSet result = statement.executeQuery()) { result.next(); return result.getLong(1); }
        }
    }
    private static double percentile(long[] values, double percentile) {
        if (values.length == 0) return 0;
        return values[Math.max(0, (int) Math.ceil(values.length * percentile) - 1)] / 1_000_000.0;
    }
}
