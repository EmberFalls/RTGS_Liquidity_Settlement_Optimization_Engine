package com.rtgs.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class AlgorithmBenchmarkMain {
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args.length > 0 ? args[0] : "docs/benchmark-results/algorithm.json");
        Files.createDirectories(output.toAbsolutePath().getParent());
        AlgorithmBenchmark benchmark = new AlgorithmBenchmark();
        WorkloadGenerator generator = new WorkloadGenerator();
        List<AlgorithmBenchmark.Result> results = new ArrayList<>();
        for (String profile : List.of("normal-day", "peak-day", "burst-day", "liquidity-constrained", "gridlock-heavy", "duplicate-heavy")) {
            Workload workload = generator.generate(WorkloadConfig.profile(profile, 300, 42));
            for (var strategy : AlgorithmBenchmark.Strategy.values()) {
                var result = benchmark.run(workload, strategy, workload.config().openingLiquidityMinor());
                results.add(result);
                System.out.println(profile + " " + result);
            }
        }
        Workload measured = generator.generate(new WorkloadConfig("liquidity-constrained", 120, 100, 3,
                10, 10, 20, WorkloadConfig.AmountDistribution.UNIFORM, .1, .3, .2, 0, 1, 42));
        long baseline = benchmark.minimumOpeningForAll(measured, AlgorithmBenchmark.Strategy.FIFO);
        long priority = benchmark.minimumOpeningForAll(measured, AlgorithmBenchmark.Strategy.PRIORITY);
        long optimized = benchmark.minimumOpeningForAll(measured, AlgorithmBenchmark.Strategy.PRIORITY_PLUS_GRIDLOCK);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("dataClassification", "MODELED ASSUMPTION");
        report.put("seed", 42);
        report.put("resultsInProfileOrder", results);
        report.put("profileOrder", List.of("normal-day", "peak-day", "burst-day", "liquidity-constrained", "gridlock-heavy", "duplicate-heavy"));
        report.put("liquiditySearchWorkload", measured.config());
        report.put("successTarget", "100% of unique payments");
        report.put("minimumPerParticipantOpeningMinor", Map.of("FIFO", baseline, "PRIORITY", priority, "PRIORITY_PLUS_GRIDLOCK", optimized));
        report.put("measuredLiquidityReductionPercent", baseline == 0 ? 0.0 : 100.0 * (baseline - optimized) / baseline);
        report.put("javaVersion", System.getProperty("java.version"));
        new ObjectMapper().findAndRegisterModules().writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        System.out.println("Saved " + output.toAbsolutePath());
    }
}
