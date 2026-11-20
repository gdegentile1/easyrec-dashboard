package com.finboxsolutions.easyrec.dashboard.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.finboxsolutions.easyrec.dashboard.TestDatabase;
import com.finboxsolutions.easyrec.dashboard.dao.DashboardDao;
import com.finboxsolutions.easyrec.dashboard.model.PatternRow;
import com.finboxsolutions.easyrec.dashboard.model.PatternSighting;
import com.finboxsolutions.easyrec.dashboard.model.PatternTrend;

/**
 * The pattern read side: trends on counts and on rates, the unexplained totals, the history
 * continued across a link, the cross-template view, linking and its refusals, the batch
 * delete, and a datasource without the pattern tables.
 */
class PatternServiceTest {

    // ------------------------------------------------------------------------- trend rules

    @Nested
    @DisplayName("Trend rules")
    class TrendRules {

        private final PatternService service = new PatternService(null, null, 0.10d);

        @Test
        @DisplayName("Appearing, holding, rising, falling and disappearing")
        void counts() {
            assertEquals(PatternTrend.NEW, service.trendOf(5, null, false), "first detection");
            assertEquals(PatternTrend.STABLE, service.trendOf(105, 100L, true), "within 10%");
            assertEquals(PatternTrend.STABLE, service.trendOf(110, 100L, true), "10% exactly");
            assertEquals(PatternTrend.INCREASING, service.trendOf(111, 100L, true));
            assertEquals(PatternTrend.DECREASING, service.trendOf(89, 100L, true));
            assertEquals(PatternTrend.NOT_DETECTED, service.trendOf(0, 4L, true));
            assertEquals(PatternTrend.STILL_NOT_DETECTED, service.trendOf(0, 0L, true));
            assertEquals(PatternTrend.STILL_NOT_DETECTED, service.trendOf(0, null, false));
            assertEquals(PatternTrend.REAPPEARED, service.trendOf(3, 0L, true));
            assertEquals(PatternTrend.NEW, service.trendOf(3, 0L, false), "first count after zeros only");
        }

        @Test
        @DisplayName("Runs of different sizes are compared on their rates")
        void rates() {
            assertEquals(PatternTrend.STABLE, service.trendOf(20, 10L, true, 5.0d, 5.0d),
                    "double the count on a run twice the size");
            assertEquals(PatternTrend.DECREASING, service.trendOf(10, 10L, true, 2.5d, 5.0d),
                    "same count on a run twice the size");
            assertEquals(PatternTrend.INCREASING, service.trendOf(20, 10L, true, 5.0d, 0.0d),
                    "no previous rate: back to counts");
        }

        @Test
        @DisplayName("The tolerance is a setting, and no trend says resolved")
        void tolerance() {
            assertEquals(PatternTrend.STABLE, new PatternService(null, null, 0.5d).trendOf(140, 100L, true));
            assertFalse(List.of(PatternTrend.values()).stream().anyMatch(trend ->
                    trend.label().toLowerCase().contains("resolved") || trend.label().toLowerCase().contains("fixed")));
        }
    }

    @Nested
    @DisplayName("Configured tolerance")
    class ConfiguredTolerance {

        @AfterEach
        void clear() {
            System.clearProperty(PatternService.STABLE_TOLERANCE_PROPERTY);
        }

        @Test
        @DisplayName("Read from the system property, the default for a missing, negative or bad value")
        void fromProperty() {
            assertEquals(PatternService.DEFAULT_STABLE_TOLERANCE, PatternService.configuredTolerance());
            System.setProperty(PatternService.STABLE_TOLERANCE_PROPERTY, " 0.25 ");
            assertEquals(0.25d, PatternService.configuredTolerance());
            System.setProperty(PatternService.STABLE_TOLERANCE_PROPERTY, "-1");
            assertEquals(PatternService.DEFAULT_STABLE_TOLERANCE, PatternService.configuredTolerance());
            System.setProperty(PatternService.STABLE_TOLERANCE_PROPERTY, "ten percent");
            assertEquals(PatternService.DEFAULT_STABLE_TOLERANCE, PatternService.configuredTolerance());
        }
    }

    // -------------------------------------------------------------------------- sample run

    @Nested
    @DisplayName("The EasyRec sample")
    class SampleRun {

        /**
         * Run 1 on the project template 2, its statistics, context and patterns on template 1,
         * six patterns counted 9, 9, 6, 1, 1, 1.
         */
        @Test
        @DisplayName("The patterns are read on the reconciliation template, not the run's")
        void sample() {
            TestDatabase database = TestDatabase.create()
                    .template(1, "/samples/csv/rec.xml")
                    .template(2, "/samples/project.xml")
                    .batch(1, "2026-10-01 08:00:00")
                    .run(1, 1, 2)
                    .context(1, 0, 1)
                    .rowStats(1, 1, 1, 100, 100, 0, 0, 27, 73);
            String[] descriptions = {
                "M_F_CTP / Single substitution / LEHMAN>DEFAULT_LEHMAN",
                "VALUE_DATE / Constant date shift / 1",
                "AMOUNT / No rule found",
                "M_F_TYPO4 / Single substitution / IT>INT",
                "M_F_TRN / Single substitution / X>Y",
                "M_F_BOOK / Single substitution / A>B",
            };
            long[] counts = {9, 9, 6, 1, 1, 1};
            for (int index = 0; index < descriptions.length; index++) {
                database.pattern(index + 1, 1, "sig" + (index + 1), descriptions[index], 1, null)
                        .patternStat(1, index + 1, counts[index]);
            }
            DashboardDao dao = database.dao();
            DashboardService dashboard = new DashboardService(dao);
            PatternService patterns = new PatternService(dao, dashboard, 0.10d);

            DashboardService.Reconciliation rec = dashboard.findReconciliations(List.of(1), null).get(0);
            assertEquals(1, rec.statsTemplateId(), "statistics on template 1, not the run's 2");
            PatternService.RunPatterns found = patterns.patternsOf(1, rec.statsTemplateId());
            assertEquals(List.of(9L, 9L, 6L, 1L, 1L, 1L),
                    found.lines().stream().map(PatternService.PatternLine::occurrences).toList());
            assertEquals(6L, found.presentCount());
            assertEquals(6L, found.count(PatternTrend.NEW));
            assertEquals(6L, found.unexplained(), "breaks with no rule found");
            assertFalse(patterns.patternsOf(1, 2).tracked(), "the run's own template has no patterns");
            assertFalse(patterns.patternsOf(1, null).tracked());
        }
    }

    // ----------------------------------------------------------------- with pattern tables

    @Nested
    @DisplayName("With pattern tables")
    class WithPatternTables {

        private DashboardDao dao;
        private PatternService patterns;

        /**
         * Template 1 is the reconciliation, 2 the project template ER_DASHBOARD_RUN points at,
         * 3 the same reconciliation after a key change, 4 another one. Runs 1 to 8 on template 1,
         * but run 8 on 3; run 6 compared twice as many rows; run 3 also reconciled template 4.
         *
         * <pre>
         * A (10) NPV value mapping      5, 5, 8, 0, 0, 3          first seen on run 1
         * B (11) GAMMA precision loss   -, -, 4, 2, 2, 2          linked to C
         * C (12) DELTA no rule found    7                         run 1 only
         * D (13) NPV no rule found      2, 2, 2, 6, 6, 2
         * E (20) on template 3          run 8: 4                  A's cause after the key change
         * F (21) on template 4          run 3: 9                  A's signature
         * </pre>
         * Run 7 has statistics but no pattern row: its export was skipped.
         */
        @BeforeEach
        void setUp() {
            TestDatabase database = TestDatabase.create()
                    .template(1, "/rec/trades.xml")
                    .template(2, "/projects/trades.xml")
                    .template(3, "/rec/trades.xml")
                    .template(4, "/rec/other.xml");
            for (int run = 1; run <= 8; run++) {
                database.batch(run, "2026-09-0" + run + " 08:00:00").run(run, run, 2);
                int template = run == 8 ? 3 : 1;
                long rows = run == 6 ? 400 : 200;
                database.context(run, 0, template).rowStats(run, template, 1, rows, rows, 0, 0, 20, rows - 20);
            }
            database.context(3, 1, 4).rowStats(3, 4, 1, 200, 200, 0, 0, 20, 180);

            database.pattern(10, 1, "aaa", "NPV / Value mapping table / EUR>USD", 1, null)
                    .pattern(12, 1, "ccc", "DELTA / No rule found", 1, null)
                    .pattern(11, 1, "bbb", "GAMMA / Precision loss / 4", 3, 12)
                    .pattern(13, 1, "ddd", "NPV / No rule found", 1, null)
                    .pattern(20, 3, "eee", "NPV / Value mapping table / EUR>USD,GBP>USD", 8, null)
                    .pattern(21, 4, "aaa", "NPV / Value mapping table / EUR>USD", 3, null);
            long[] a = {5, 5, 8, 0, 0, 3};
            long[] d = {2, 2, 2, 6, 6, 2};
            for (int run = 1; run <= 6; run++) {
                database.patternStat(run, 10, a[run - 1]).patternStat(run, 13, d[run - 1]);
                if (run >= 3) {
                    database.patternStat(run, 11, run == 3 ? 4 : 2);
                }
            }
            database.patternStat(1, 12, 7).patternStat(3, 21, 9).patternStat(8, 20, 4);

            dao = database.dao();
            patterns = new PatternService(dao, new DashboardService(dao), 0.10d);
        }

        @Test
        @DisplayName("A run's patterns: most occurrences first, with trend, change and share of rows")
        void runPatterns() {
            PatternService.RunPatterns run3 = patterns.patternsOf(3, 1);
            assertTrue(run3.tracked());
            assertTrue(run3.exported());
            assertEquals(3, run3.lines().size(), "the three patterns it counted");
            PatternService.PatternLine a = run3.lines().get(0);
            assertEquals(10, a.pattern().patternId(), "most occurrences first");
            assertEquals(PatternTrend.INCREASING, a.trend());
            assertEquals(3L, a.change());
            assertEquals(5L, a.previous());
            assertEquals(4.0d, a.shareOfRows(), "8 of 200 rows");
            assertEquals(PatternTrend.NEW, run3.lines().get(1).trend(), "B");
            assertEquals(4, run3.knownCount(), "four patterns known on the template");
            assertEquals(2L, run3.unexplained());
            assertFalse(patterns.patternsOf(3, 2).tracked(), "the run's own template finds nothing");
        }

        @Test
        @DisplayName("Disappearing, staying away and coming back; a shrinking rate on a bigger run")
        void trendsAcrossRuns() {
            PatternService.RunPatterns run4 = patterns.patternsOf(4, 1);
            assertEquals(PatternTrend.NOT_DETECTED, lineOf(run4, 10).trend());
            assertEquals(6L, lineOf(run4, 10).unexplainedOnColumn(), "NPV's unexplained count beside it");
            assertNull(lineOf(run4, 11).unexplainedOnColumn(), "a detected pattern shows none");
            assertEquals(PatternTrend.DECREASING, lineOf(run4, 11).trend());
            assertEquals(PatternTrend.STILL_NOT_DETECTED, lineOf(patterns.patternsOf(5, 1), 10).trend());

            PatternService.RunPatterns run6 = patterns.patternsOf(6, 1);
            assertEquals(PatternTrend.REAPPEARED, lineOf(run6, 10).trend());
            assertEquals(PatternTrend.DECREASING, lineOf(run6, 11).trend(), "same count on twice the rows");
            assertEquals(0.5d, lineOf(run6, 11).shareOfRows(), "2 of 400 rows");
        }

        @Test
        @DisplayName("A skipped export has no rows rather than zeros, and no unexplained figure")
        void skippedExport() {
            PatternService.RunPatterns run7 = patterns.patternsOf(7, 1);
            assertTrue(run7.tracked());
            assertFalse(run7.exported());

            Map<Integer, Long> unexplained = patterns.unexplainedByRun(1, List.of(1, 2, 3, 4, 5, 6, 7));
            assertEquals(9L, unexplained.get(1), "C + D");
            assertEquals(6L, unexplained.get(4));
            assertFalse(unexplained.containsKey(7));
        }

        @Test
        @DisplayName("A pattern's history: its points, its peak, and the runs it was detected on")
        void history() {
            PatternService.PatternHistory history = patterns.patternHistory(10);
            assertEquals(6, history.points().size());
            assertEquals(4, history.detectedRuns());
            assertEquals(3, history.peak().runId());
            assertEquals(1, history.points().get(0).contextTemplateId());
            assertEquals(6, history.points().get(5).batch().batchId());
            assertEquals(6, history.latest().runId());
            assertEquals(6, history.newestFirst().get(0).runId());
            assertTrue(history.runSizesDiffer());
            assertEquals("/rec/trades.xml", history.template().fullPath());
            assertNull(history.linkedPattern());
            assertEquals("2026-09-01 08:00", history.points().get(0).longLabel());

            PatternService.PatternHistory b = patterns.patternHistory(11);
            assertEquals(12, b.linkedPattern().patternId(), "B continues C");
            assertEquals(5, b.points().size(), "B's history runs on through C");
            assertNull(patterns.patternHistory(999));
        }

        @Test
        @DisplayName("The same signature on other templates, with its count on the run")
        void sightings() {
            List<PatternSighting> sightings = patterns.sightings(dao.findPattern(10), 3);
            assertEquals(2, sightings.size());
            assertEquals("/rec/other.xml", sightings.get(0).templatePath(), "ordered by path");
            assertEquals(9L, sightings.get(0).occurrences());
            assertEquals(8L, sightings.get(1).occurrences());
            assertNull(patterns.sightings(dao.findPattern(10), 8).get(1).occurrences(),
                    "no stat row is no count, not zero");
        }

        @Test
        @DisplayName("Linking: candidates on the same path, refusals of self-links and cycles, history across")
        void linking() {
            assertEquals(List.of(10, 12, 13, 11), ids(patterns.linkCandidates(20)));
            assertThrows(IllegalArgumentException.class, () -> patterns.link(20, 20));
            assertEquals(1, patterns.link(20, 10));
            assertEquals(10, dao.findPattern(20).linkedPatternId());
            assertFalse(ids(patterns.linkCandidates(10)).contains(20), "A may not link back to E");
            assertThrows(IllegalArgumentException.class, () -> patterns.link(10, 20), "a cycle");
            assertNull(dao.findPattern(10).linkedPatternId(), "untouched by the refusal");

            PatternService.PatternHistory continued = patterns.patternHistory(20);
            assertEquals(7, continued.points().size(), "continues across the template change");
            assertEquals(10, continued.points().get(0).countedAs().patternId());
            assertEquals(20, continued.latest().countedAs().patternId());

            assertEquals(1, patterns.link(20, null));
            assertNull(dao.findPattern(20).linkedPatternId());
        }

        @Test
        @DisplayName("Deleting a batch moves or removes the patterns first seen on its run")
        void deleteBatch() {
            Map<String, Integer> removed = dao.deleteBatches(List.of(1));
            assertEquals(3, removed.get("ER_DASHBOARD_PATTERN_STAT"), "run 1's pattern stats");
            assertEquals(1, removed.get("ER_DASHBOARD_PATTERN"), "C, left with no history");
            assertNull(dao.findPattern(12));
            assertEquals(2, dao.findPattern(10).firstSeenRun(), "moved to the earliest run still counting it");
            assertNull(dao.findPattern(11).linkedPatternId(), "B's link to C cleared");
            assertEquals(PatternTrend.NEW, lineOf(patterns.patternsOf(2, 1), 10).trend());
        }
    }

    // -------------------------------------------------------------- without pattern tables

    @Test
    @DisplayName("Without the pattern tables nothing is tracked, and a batch is still deleted")
    void withoutPatternTables() {
        TestDatabase database = TestDatabase.create(false)
                .template(1, "/rec/a.xml")
                .batch(1, null)
                .run(1, 1, 1);
        DashboardDao dao = database.dao();
        PatternService patterns = new PatternService(dao, new DashboardService(dao), 0.10d);
        assertFalse(patterns.patternsOf(1, 1).tracked());
        assertNull(patterns.patternHistory(1));
        assertTrue(patterns.unexplainedByRun(1, List.of(1)).isEmpty());
        Map<String, Integer> removed = dao.deleteBatches(List.of(1));
        assertEquals(1, removed.get("ER_DASHBOARD_BATCH"));
        assertFalse(removed.containsKey("ER_DASHBOARD_PATTERN_STAT"));
    }

    // ---------------------------------------------------------------------------- helpers

    private static PatternService.PatternLine lineOf(PatternService.RunPatterns found, int patternId) {
        return found.lines().stream().filter(line -> line.pattern().patternId() == patternId).findFirst()
                .orElseThrow(() -> new AssertionError("pattern " + patternId + " not on the run"));
    }

    private static List<Integer> ids(List<PatternRow> rows) {
        List<Integer> ids = new ArrayList<>();
        rows.forEach(row -> ids.add(row.patternId()));
        return ids;
    }
}
