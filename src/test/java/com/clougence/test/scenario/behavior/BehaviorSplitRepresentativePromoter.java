package com.clougence.test.scenario.behavior;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.clougence.clouddm.sdk.sql.analysis.behavior.BehaviorAnalysisSpi;
import com.clougence.clouddm.sdk.sql.analysis.behavior.StatementBehavior;
import com.clougence.test.framework.DialectRuntime;
import com.clougence.test.framework.TestPlan.DialectConfig;

/** Promotes one production-analyzed behavior case for every split classification shape. */
public final class BehaviorSplitRepresentativePromoter {

    private static final String  LONG_DELIMITER = "------------------------------------------------------------------------------------------";
    private static final Pattern CASE_DELIMITER = Pattern.compile("(?m)^----------\\s*$");
    private static final Pattern HEADER         = Pattern.compile("^\\[([^]]+)](.*)$", Pattern.DOTALL);

    private BehaviorSplitRepresentativePromoter(){
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = options(args);
        Path splitRoot = Path.of(required(options, "split-root")).toAbsolutePath().normalize();
        Path output = Path.of(required(options, "output")).toAbsolutePath().normalize();
        String engineClass = required(options, "engine-class");
        String datasource = required(options, "datasource");
        String version = options.get("version");
        String levels = options.getOrDefault("levels", "/test/1/catalog1/schema1");
        Set<String> includeClassifications = csv(options.get("include-classifications"));

        DialectRuntime runtime = new DialectRuntime(new DialectConfig(datasource, engineClass, datasource, null), version);
        BehaviorAnalysisSpi spi = runtime.engine().behaviorAnalysisSpi(runtime.parameters());
        if (spi == null)
            throw new IllegalStateException("No BehaviorAnalysisSpi for " + datasource);

        Map<String, List<Candidate>> candidates = new LinkedHashMap<>();
        try (var paths = Files.walk(splitRoot)) {
            for (Path path : paths.filter(Files::isRegularFile).filter(value -> value.toString().endsWith(".txt")).sorted().toList()) {
                if (path.toString().contains("/reject/"))
                    continue;
                String content = Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
                int expectedStart = content.indexOf(LONG_DELIMITER);
                if (expectedStart < 0)
                    throw new IllegalStateException("Missing split delimiter: " + path);
                String expected = content.substring(expectedStart + LONG_DELIMITER.length());
                int caseIndex = 0;
                for (String rawBlock : CASE_DELIMITER.split(expected)) {
                    String block = rawBlock.strip();
                    if (block.isEmpty())
                        continue;
                    Matcher header = HEADER.matcher(block);
                    if (!header.matches())
                        throw new IllegalStateException("Invalid split block: " + path);
                    String classification = header.group(1).strip();
                    if (!includeClassifications.isEmpty() && !includeClassifications.contains(classification)) {
                        caseIndex++;
                        continue;
                    }
                    String sql = header.group(2).strip();
                    if (!sql.isEmpty()) {
                        List<Candidate> values = candidates.computeIfAbsent(classification, ignored -> new ArrayList<>());
                        if (values.size() < 64)
                            values.add(new Candidate(path, caseIndex, classification, sql));
                    }
                    caseIndex++;
                }
            }
        }

        StringBuilder result = new StringBuilder();
        StringBuilder failures = new StringBuilder();
        int promoted = 0;
        for (Map.Entry<String, List<Candidate>> entry : candidates.entrySet()) {
            Candidate candidate = null;
            List<StatementBehavior> actual = null;
            RuntimeException lastError = null;
            for (Candidate value : entry.getValue()) {
                try {
                    List<StatementBehavior> analyzed = spi.analysisBehavior(value.sql(), BehaviorTextTest.parseLevels(levels, value.classification()), 1, 0);
                    if (analyzed != null && !analyzed.isEmpty()) {
                        candidate = value;
                        actual = analyzed;
                        break;
                    }
                } catch (RuntimeException error) {
                    lastError = error;
                }
            }
            if (candidate == null) {
                failures.append(entry.getKey()).append("\t").append(lastError == null ? "no behavior" : lastError.getMessage()).append("\n");
                continue;
            }
            if (promoted++ > 0)
                result.append("\n----------\n");
            result.append('[')
                .append(caseName(candidate))
                .append("]\n")
                .append("sql:\n")
                .append(candidate.sql())
                .append("\n")
                .append("levels:\n")
                .append(levels)
                .append("\n")
                .append("expect:\n")
                .append(BehaviorExpectationRecorder.render(actual))
                .append("\n");
        }
        Files.createDirectories(output.getParent());
        Files.writeString(output, result.toString(), StandardCharsets.UTF_8);
        if (!failures.isEmpty()) {
            Path failureOutput = Path.of(output + ".failures.txt");
            Files.writeString(failureOutput, failures.toString(), StandardCharsets.UTF_8);
            throw new IllegalStateException("No analyzable representative for " + failures.toString().lines().count() + " classifications; see " + failureOutput);
        }
        System.out.println("[behavior-promoter] datasource=" + datasource + " classifications=" + candidates.size() + " cases=" + promoted + " output=" + output);
    }

    private static String caseName(Candidate candidate) throws Exception {
        String slug = candidate.classification().toLowerCase().replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(candidate.sql().getBytes(StandardCharsets.UTF_8));
        StringBuilder hash = new StringBuilder();
        for (int index = 0; index < 6; index++)
            hash.append(String.format("%02x", digest[index]));
        return slug + "__split_representative_" + hash;
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int index = 0; index < args.length; index++) {
            if (!args[index].startsWith("--") || index + 1 >= args.length)
                throw new IllegalArgumentException("Expected --key value");
            result.put(args[index].substring(2), args[++index]);
        }
        return result;
    }

    private static String required(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank())
            throw new IllegalArgumentException("Missing --" + name);
        return value;
    }

    private static Set<String> csv(String value) {
        if (value == null || value.isBlank())
            return Set.of();
        return java.util.Arrays.stream(value.split(",")).map(String::strip).filter(item -> !item.isEmpty()).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private record Candidate(Path source, int index, String classification, String sql) {
    }
}
