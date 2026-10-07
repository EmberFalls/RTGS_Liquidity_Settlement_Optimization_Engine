package com.rtgs.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;

public class GeneratorMain {
    public static void main(String[] args) throws Exception {
        String profile = args.length > 0 ? args[0] : "normal-day";
        int count = args.length > 1 ? Integer.parseInt(args[1]) : 1000;
        long seed = args.length > 2 ? Long.parseLong(args[2]) : 42;
        Path output = Path.of(args.length > 3 ? args[3] : "target/workload.json");
        if (output.toAbsolutePath().getParent() != null) Files.createDirectories(output.toAbsolutePath().getParent());
        new ObjectMapper().findAndRegisterModules().writerWithDefaultPrettyPrinter()
                .writeValue(output.toFile(), new WorkloadGenerator().generate(WorkloadConfig.profile(profile, count, seed)));
        System.out.println("Generated " + count + " modeled attempts: " + output.toAbsolutePath());
    }
}
