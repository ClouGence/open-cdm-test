# open-cdm-test

`open-cdm-test` is the standalone SQL test project extracted from
`open-cdm-copy-1/tests/ds-test`. Its execution framework does not depend on
JUnit; JUnit is only one of the two supported entry points.

The project expects this directory layout:

```text
dm/
├── open-cdm/
└── open-cdm-test/
```

Gradle currently uses `includeBuild("../open-cdm-copy-1/backend")` to substitute every
`com.cdmgr:<module>:<version>` dependency with the corresponding module from
the sibling source tree. The location is controlled by `openCdmBackendDir` in
`gradle.properties`; set it to `../open-cdm/backend` to switch back to the main
checkout after its ongoing interface migration is complete.

## Test model

The project has three layers:

1. Test resources under `src/test/resources/{split,lineage,behavior}`.
2. The independent loader, bounded queue, workers, and result collector under
   `src/test/java/com/clougence/test/framework`.
3. Data-source and version-specific dialect configuration in
   `src/test/resources/config/test-plan.json`.

Configurable resource producers claim distinct sources through a shared atomic
cursor and submit their cases to a bounded queue. Independent workers consume
that queue. `workers: 0` selects the available processor count at runtime;
producer count, queue capacity, and worker count can all be overridden when
starting a run.

ANTLR prediction caches are retained in one process-wide, fixed-size slot
pool. A borrowed slot is exclusive, but after it is returned the same warmed
cache can be reused by any worker thread. `antlrCacheSlots` limits retained
cache copies, while `antlrMaxSlotsPerKey` limits how many retained slots may
belong to the same data source, parser version, and feature configuration. If
that limit is reached or all retained slots are busy, parsing uses a worker-local
overflow cache instead of reducing worker concurrency. The overflow cache is
reused only while that worker continues with the same parser key and never
occupies the global retained pool. After a parse finishes, a cache with more
than `antlrMaxDfaStatesPerSlot` combined Lexer and Parser DFA states is discarded
as a whole and rebuilt cold on its next use.

During execution the collector prints one summary line per second containing
source progress, submitted cases, queue occupancy, pass/fail counts, throughput,
and elapsed time. Case counters are weighted by the actual cases in a
resource: a split fixture containing 100 expected statements contributes 100
cases even though it is executed as one queue task. A final summary line is
always printed. While sources are still being lazily loaded, case progress is
shown as `completed/discovered+`; the `+` disappears once all sources have been
loaded and the denominator is the exact total case count. The `rate` field is
the number of cases completed between adjacent progress reports and uses the
`rps` suffix. `source` counts resource/variant entries, `queue` counts batch
tasks, and `passed` and `failed` count individual cases.

Failures are written to standard error as soon as they occur. Each failure block
contains the resource script path, test case name, case SQL, and the complete
exception stack trace. Failure blocks are assembled before printing so parallel
workers cannot interleave their stack traces.

The same failure blocks are flushed immediately to
`build/reports/open-cdm-test/failures.log`. The file is truncated when a run
starts, so an empty file means that run produced no test failures.

After execution finishes, the framework prints one datasource summary line. It
reports `total`, `passed`, and `failed` case counts independently for `behavior`,
`lineage`, and `split`; an unselected or empty domain is reported as zero. These
are case counts rather than resource-file or queue-task counts. For example:

```text
[open-cdm-test][DATASOURCE] datasource=doris behavior(total=0,passed=0,failed=0) lineage(total=0,passed=0,failed=0) split(total=2,passed=0,failed=2)
```

## Entry points

Run through the standalone `main` entry point (no JUnit execution):

```bash
./gradlew runTests
```

Run through JUnit:

```bash
./gradlew test
```

JUnit exposes exactly one test method, `UnifiedTest.runConfiguredSuites`, and
delegates all loading, scheduling, execution, and collection to the same
standalone framework used by `TestMain`.

The standalone entry accepts optional filters and runtime overrides:

```bash
./gradlew runTests -PtestArgs='--domain=split --datasource=tidb --version=7 --producers=2 --workers=8 --queue-capacity=1024 --antlr-cache-slots=8 --antlr-max-slots-per-key=3 --antlr-max-dfa-states-per-slot=10000'
```

Available filters are `domain`, `datasource`, `version`, and `resource`.
Equivalent JUnit system properties use the `test.` prefix:

```bash
./gradlew test -Dtest.domain=split -Dtest.datasource=tidb -Dtest.version=7 -Dtest.producers=2 -Dtest.workers=8 -Dtest.queueCapacity=1024 -Dtest.antlrCacheSlots=8 -Dtest.antlrMaxSlotsPerKey=3 -Dtest.antlrMaxDfaStatesPerSlot=10000
```
