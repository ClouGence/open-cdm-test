package com.clougence.test.scenario.behavior;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.clougence.clouddm.sdk.sql.analysis.behavior.BehaviorAnalysisSpi;
import com.clougence.clouddm.sdk.sql.analysis.behavior.BehaviorObject;
import com.clougence.clouddm.sdk.sql.analysis.behavior.BehaviorRelation;
import com.clougence.clouddm.sdk.sql.analysis.behavior.StatementBehavior;
import com.clougence.test.framework.DialectRuntime;
import com.clougence.test.framework.TestPlan.DialectConfig;
import com.clougence.test.framework.resource.TextCaseSupport;
import com.clougence.test.framework.resource.TextCaseSupport.CaseBlock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Records newly non-empty behavior output only where a fixture deliberately had an empty expectation. */
public final class BehaviorExpectationRecorder {

    private static final ObjectMapper JSON      = new ObjectMapper();
    private static final Pattern      DELIMITER = Pattern.compile("(?m)^----------\\s*$");

    private BehaviorExpectationRecorder(){
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = options(args);
        Path resourceRoot = Path.of(required(options, "resource-root")).toAbsolutePath().normalize();
        String resourcePrefix = required(options, "resource-prefix");
        String engineClass = required(options, "engine-class");
        String datasource = required(options, "datasource");
        String version = options.get("version");
        boolean refreshFallback = Boolean.parseBoolean(options.getOrDefault("refresh-fallback", "false"));
        boolean refreshAll = Boolean.parseBoolean(options.getOrDefault("refresh-all", "false"));
        Set<String> refreshTypes = csv(options.get("refresh-types"));
        Set<String> includeFiles = csv(options.get("include-files"));
        Set<String> includeCases = csv(options.get("include-cases"));
        Set<String> includeCaseKeys = csv(options.get("include-case-keys"));
        DialectRuntime runtime = new DialectRuntime(new DialectConfig(datasource, engineClass, datasource, null), version);
        BehaviorAnalysisSpi spi = runtime.engine().behaviorAnalysisSpi(runtime.parameters());
        if (spi == null) {
            throw new IllegalStateException("No BehaviorAnalysisSpi for " + datasource);
        }

        int files = 0;
        int cases = 0;
        try (var paths = Files.walk(resourceRoot)) {
            for (Path path : paths.filter(Files::isRegularFile).filter(value -> value.toString().endsWith(".txt")).sorted().toList()) {
                if (!includeFiles.isEmpty() && !includeFiles.contains(path.getFileName().toString())) {
                    continue;
                }
                String content = Files.readString(path, StandardCharsets.UTF_8);
                RecordResult result = record(resourcePrefix + "/"
                                             + resourceRoot.relativize(path)
                                                 .toString()
                                                 .replace('\\', '/'), content, spi, refreshFallback, refreshAll, refreshTypes, includeCases, includeCaseKeys);
                if (result.changedCases() > 0) {
                    Files.writeString(path, result.content(), StandardCharsets.UTF_8);
                    files++;
                    cases += result.changedCases();
                }
            }
        }
        System.out.println("[behavior-recorder] datasource=" + datasource + " files=" + files + " cases=" + cases);
    }

    private static RecordResult record(String resourcePath, String content, BehaviorAnalysisSpi spi, boolean refreshFallback, boolean refreshAll, Set<String> refreshTypes,
                                       Set<String> includeCases, Set<String> includeCaseKeys) throws IOException {
        Matcher matcher = DELIMITER.matcher(content);
        List<String> segments = new ArrayList<>();
        List<String> delimiters = new ArrayList<>();
        int start = 0;
        while (matcher.find()) {
            segments.add(content.substring(start, matcher.start()));
            delimiters.add(content.substring(matcher.start(), matcher.end()));
            start = matcher.end();
        }
        segments.add(content.substring(start));

        int changed = 0;
        for (int index = 0; index < segments.size(); index++) {
            String segment = segments.get(index);
            String blockText = segment.strip();
            if (blockText.isEmpty()) {
                continue;
            }
            List<CaseBlock> blocks = TextCaseSupport.parseBlocks(resourcePath, blockText);
            if (blocks.size() != 1) {
                throw new IllegalStateException("Expected one case block in " + resourcePath);
            }
            BehaviorTextTest.TestCase testCase = BehaviorTextTest.parseOneCase(blocks.get(0));
            if (!includeCases.isEmpty() && !includeCases.contains(testCase.name())) {
                continue;
            }
            if (!includeCaseKeys.isEmpty() && !includeCaseKeys.contains(resourcePath + "#" + testCase.name())) {
                continue;
            }
            if (!refreshAll && !emptyExpectation(testCase.expectJson()) && !(refreshFallback && fallbackExpectation(testCase.expectJson()))
                && !matchesStatementType(testCase.expectJson(), refreshTypes)) {
                continue;
            }
            List<StatementBehavior> actual;
            try {
                actual = spi.analysisBehavior(testCase.sql(), testCase.levels(), testCase.baseLine(), testCase.baseColumn());
            } catch (RuntimeException error) {
                throw new IllegalStateException("Cannot refresh " + resourcePath + "#" + testCase.name(), error);
            }
            if (actual == null || actual.stream().allMatch(statement -> statement.getRelations() == null || statement.getRelations().isEmpty())) {
                continue;
            }
            int expect = segment.indexOf("expect:");
            if (expect < 0) {
                throw new IllegalStateException("Missing expect section in " + resourcePath + "#" + testCase.name());
            }
            String prefix = segment.substring(0, expect + "expect:".length());
            String suffix = trailingWhitespace(segment);
            segments.set(index, prefix + System.lineSeparator() + render(actual) + suffix);
            changed++;
        }

        StringBuilder result = new StringBuilder();
        for (int index = 0; index < segments.size(); index++) {
            result.append(segments.get(index));
            if (index < delimiters.size()) {
                result.append(delimiters.get(index));
            }
        }
        return new RecordResult(result.toString(), changed);
    }

    private static boolean matchesStatementType(String text, Set<String> refreshTypes) throws IOException {
        if (refreshTypes.isEmpty())
            return false;
        JsonNode root = JSON.readTree(text);
        if (root == null || !root.isObject())
            return false;
        Iterator<String> names = root.fieldNames();
        while (names.hasNext()) {
            if (refreshTypes.contains(names.next()))
                return true;
        }
        return false;
    }

    private static boolean emptyExpectation(String text) throws IOException {
        JsonNode root = JSON.readTree(text);
        if (root == null || !root.isObject() || root.isEmpty()) {
            return false;
        }
        Iterator<JsonNode> values = root.elements();
        while (values.hasNext()) {
            JsonNode value = values.next();
            if (!value.isArray() || !value.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static boolean fallbackExpectation(String text) throws IOException {
        JsonNode root = JSON.readTree(text);
        if (root == null || !root.isObject() || root.isEmpty()) {
            return false;
        }
        boolean found = false;
        for (JsonNode relations : root) {
            if (!relations.isArray() || relations.isEmpty()) {
                return false;
            }
            for (JsonNode relation : relations) {
                JsonNode subject = relation.get("subject");
                String value = subject != null && subject.isObject() ? subject.path("value").asText() : subject == null ? "" : subject.asText();
                if (!(value.startsWith("Query(") || value.startsWith("Unknown("))) {
                    return false;
                }
                found = true;
            }
        }
        return found;
    }

    static String render(List<StatementBehavior> statements) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        for (StatementBehavior statement : statements) {
            ArrayNode relations = root.putArray(statement.getStatementType().name());
            for (BehaviorRelation relation : statement.getRelations()) {
                ObjectNode item = relations.addObject();
                item.put("subject", objectText(relation.getSubject()));
                item.put("action", relation.getAction().name());
                if (relation.getTarget() != null && relation.getTarget().size() == 1) {
                    item.put("target", objectText(relation.getTarget().get(0)));
                } else if (relation.getTarget() != null && relation.getTarget().size() > 1) {
                    ArrayNode targets = item.putArray("target");
                    relation.getTarget().forEach(target -> targets.add(objectText(target)));
                }
            }
        }
        return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root);
    }

    private static String objectText(BehaviorObject object) {
        return object.getObjectType() + "(" + object.getStartLine() + ":" + object.getStartColumn() + "~" + object.getEndLine() + ":" + object.getEndColumn() + ") "
               + object.getObjectPath();
    }

    private static String trailingWhitespace(String text) {
        int index = text.length();
        while (index > 0 && Character.isWhitespace(text.charAt(index - 1))) {
            index--;
        }
        return text.substring(index);
    }

    private static Map<String, String> options(String[] args) {
        java.util.LinkedHashMap<String, String> result = new java.util.LinkedHashMap<>();
        for (int index = 0; index < args.length; index++) {
            if (!args[index].startsWith("--") || index + 1 >= args.length) {
                throw new IllegalArgumentException("Expected --key value, got " + args[index]);
            }
            result.put(args[index].substring(2), args[++index]);
        }
        return result;
    }

    private static String required(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing --" + name);
        }
        return value;
    }

    private static Set<String> csv(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return java.util.Arrays.stream(value.split(",")).map(String::strip).filter(item -> !item.isEmpty()).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private record RecordResult(String content, int changedCases) {
    }
}
