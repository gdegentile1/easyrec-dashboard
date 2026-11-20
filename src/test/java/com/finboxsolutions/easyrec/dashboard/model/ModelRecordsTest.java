package com.finboxsolutions.easyrec.dashboard.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The arithmetic and the fallbacks the model records carry. */
class ModelRecordsTest {

    @Test
    @DisplayName("The two STATUS mappings are opposite, -1 is an error in both, anything else unknown")
    void statusScopes() {
        assertEquals(StatusLabel.PASSED, StatusScope.EXECUTION.labelOf(1));
        assertEquals(StatusLabel.FAILED, StatusScope.EXECUTION.labelOf(0));
        assertEquals(StatusLabel.PASSED, StatusScope.RECONCILIATION.labelOf(0));
        assertEquals(StatusLabel.FAILED, StatusScope.RECONCILIATION.labelOf(1));
        for (StatusScope scope : StatusScope.values()) {
            assertEquals(StatusLabel.ERROR, scope.labelOf(StatusScope.ERROR_CODE), scope.name());
            assertEquals(StatusLabel.UNKNOWN, scope.labelOf(null), scope.name());
            assertEquals(StatusLabel.UNKNOWN, scope.labelOf(7), scope.name());
        }
        assertTrue(StatusLabel.PASSED.isPassed());
        assertFalse(StatusLabel.ERROR.isPassed());
        assertTrue(StatusLabel.FAILED.isFailed());
        assertFalse(StatusLabel.UNKNOWN.isFailed());
    }

    @Test
    @DisplayName("Each record reads its STATUS with the mapping of its table")
    void statusOfEachRecord() {
        assertEquals(StatusLabel.PASSED, batch(1, null).status(), "batch: 1 is a clean execution");
        assertEquals(StatusLabel.PASSED, run(1).status(), "run: 1 is a clean execution");
        assertEquals(StatusLabel.FAILED, rowStats(1, 100, 100, 0, 0, 10, 90).status(), "statistics: 1 is breaks found");
        assertEquals(StatusLabel.PASSED, context(0).status(), "context: 0 is no breaks");
    }

    @Test
    @DisplayName("RowStats divides by the larger side and counts every kind of break")
    void rowStats() {
        RowStats stats = rowStats(0, 80, 100, 3, 2, 5, 90);
        assertEquals(100L, stats.totalRows());
        assertEquals(10L, stats.breakCount());
        assertEquals(90.0d, stats.matchRate());
        assertEquals(5.0d, stats.shareOfRows(5));

        RowStats empty = rowStats(0, 0, 0, 0, 0, 0, 0);
        assertNull(empty.matchRate(), "nothing compared: no rate");
        assertNull(empty.shareOfRows(1));
    }

    @Test
    @DisplayName("BatchRow has no timestamp without epoch millis, and reads it in the local zone")
    void batchWhen() {
        assertNull(batch(1, null).when());
        long millis = 1_759_305_600_000L;
        assertEquals(LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault()),
                batch(1, millis).when());
    }

    @Test
    @DisplayName("PatternStat compares on the larger side and has no rate without rows")
    void patternStat() {
        PatternRow pattern = new PatternRow(1, 1, "sig", "d", 1, null);
        assertNull(new PatternStat(1, pattern, 5, true, null, null, null).rowsCompared());
        assertNull(new PatternStat(1, pattern, 5, true, null, null, null).rate());
        assertEquals(40L, new PatternStat(1, pattern, 5, true, null, null, 40L).rowsCompared());
        assertEquals(50L, new PatternStat(1, pattern, 5, true, null, 50L, 40L).rowsCompared());
        assertEquals(10.0d, new PatternStat(1, pattern, 5, true, null, 50L, 40L).rate());
        assertNull(new PatternStat(1, pattern, 5, true, null, 0L, 0L).rate(), "zero rows: no rate");
        assertEquals(1, new PatternStat(1, pattern, 5, true, null, 0L, 0L).patternId());
    }

    @Test
    @DisplayName("ColumnStats scales its stored ratio to a percentage")
    void columnStatsPercentage() {
        assertEquals(87.5d, columnStats(0.875d).matchPercentage(), 1e-9);
        assertNull(columnStats(null).matchPercentage());
    }

    @Test
    @DisplayName("PivotRow fills the two totals, reads a missing metric as zero and copies its inputs")
    void pivotRow() {
        Map<PivotMetric, Double> stored = new EnumMap<>(PivotMetric.class);
        stored.put(PivotMetric.MISSING_SRC_COUNT, 1.0d);
        stored.put(PivotMetric.MISSING_TRG_COUNT, 2.0d);
        stored.put(PivotMetric.UNMATCH_COUNT, 3.0d);
        stored.put(PivotMetric.MISSING_SRC_IMPACT_ABS, 10.0d);
        stored.put(PivotMetric.UNMATCH_IMPACT_ABS, 5.5d);
        Map<PivotMetric, Double> metrics = PivotRow.withTotals(stored);
        assertEquals(6.0d, metrics.get(PivotMetric.TOTAL_BREAKS));
        assertEquals(15.5d, metrics.get(PivotMetric.TOTAL_IMPACT_ABS));

        List<String> keys = new ArrayList<>(List.of("EUR"));
        PivotRow row = new PivotRow(1, 2, "EUR", 0, keys, metrics);
        keys.add("changed");
        assertEquals(List.of("EUR"), row.keys(), "a defensive copy");
        assertEquals(0.0d, row.metric(PivotMetric.MATCH_SUM_S));
        assertEquals(3.0d, row.metric(PivotMetric.UNMATCH_COUNT));
        assertThrows(UnsupportedOperationException.class, () -> row.metrics().put(PivotMetric.MATCH_COUNT, 1.0d));
    }

    @Test
    @DisplayName("Only the impact percentage is a ratio, and the six default columns carry the live pivot's names")
    void pivotMetrics() {
        for (PivotMetric metric : PivotMetric.values()) {
            if (metric == PivotMetric.UNMATCH_IMPACT_PCT) {
                assertEquals(List.of(PivotMetric.UNMATCH_IMPACT, PivotMetric.UNMATCH_SUM_S),
                        List.of(metric.derivedFrom()));
            } else {
                assertNull(metric.derivedFrom(), metric.name());
            }
        }
        assertEquals(List.of("Count", "Sum Source", "Sum Target", "Impact", "Impact Abs", "Impact PCT"),
                PivotMetric.DEFAULT_COLUMNS.stream().map(PivotMetric::label).toList());
        assertEquals(List.of(PivotMetric.UNMATCH_IMPACT_PCT), PivotMetric.PERCENT_COLUMNS);
    }

    @Test
    @DisplayName("TemplateRow's short name is the last segment, with either separator")
    void templateShortName() {
        assertEquals("rec.xml", new TemplateRow(1, "/samples/csv/rec.xml", null, null).shortName());
        assertEquals("rec.xml", new TemplateRow(1, "C:\\samples\\rec.xml", null, null).shortName());
        assertEquals("rec", new TemplateRow(1, "rec", null, null).shortName());
        assertEquals("Template 5", new TemplateRow(5, null, null, null).shortName());
        assertEquals("Template 5", new TemplateRow(5, "", null, null).shortName());
    }

    @Test
    @DisplayName("PatternRow falls back to its signature, then to its id, for a label")
    void patternLabel() {
        assertEquals("AMOUNT / No rule found", new PatternRow(7, 1, "abc", "AMOUNT / No rule found", 1, null).label());
        assertEquals("Pattern 0123456789ab",
                new PatternRow(7, 1, "0123456789abcdef", " ", 1, null).label());
        assertEquals("Pattern abc", new PatternRow(7, 1, "abc", null, 1, null).label());
        assertEquals("Pattern 7", new PatternRow(7, 1, null, null, 1, null).label());
    }

    @Test
    @DisplayName("Only an appearance or a rise needs attention, and only the two zero trends are absent")
    void patternTrends() {
        List<PatternTrend> attention = new ArrayList<>();
        List<PatternTrend> absent = new ArrayList<>();
        for (PatternTrend trend : PatternTrend.values()) {
            if (trend.needsAttention()) {
                attention.add(trend);
            }
            if (!trend.isPresent()) {
                absent.add(trend);
            }
            assertEquals(trend.label(), trend.toString());
        }
        assertEquals(List.of(PatternTrend.NEW, PatternTrend.REAPPEARED, PatternTrend.INCREASING), attention);
        assertEquals(List.of(PatternTrend.NOT_DETECTED, PatternTrend.STILL_NOT_DETECTED), absent);
    }

    // ---------------------------------------------------------------------------- helpers

    private static BatchRow batch(Integer status, Long millis) {
        return new BatchRow(1, "1.0", null, "ops", status, millis, null, null, 0);
    }

    private static RunRow run(Integer status) {
        return new RunRow(1, 1, "0", 1, null, null, null, null, null, status, null, null, null, 0);
    }

    private static RunContextRow context(Integer status) {
        return new RunContextRow(1, 0, 1, null, null, null, null, null, null, null, null, null, null, null,
                null, null, status, null, null);
    }

    static RowStats rowStats(Integer status, long source, long target, long missingSource, long missingTarget,
                             long unmatched, long matched) {
        return new RowStats(1, 1, status, source, target, missingSource, missingTarget, unmatched, matched,
                0, 0, null, null, null);
    }

    private static ColumnStats columnStats(Double ratio) {
        return new ColumnStats(1, 1, "AMOUNT", "Double", null, ratio, 0, 0, 0, 0, null, null, null, null,
                null, null, null, null);
    }
}
