package com.finboxsolutions.easyrec.dashboard.ui.table;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.table.TableModel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.finboxsolutions.easyrec.dashboard.model.BatchRow;
import com.finboxsolutions.easyrec.dashboard.model.ColumnStats;
import com.finboxsolutions.easyrec.dashboard.model.PatternRow;
import com.finboxsolutions.easyrec.dashboard.model.PatternSighting;
import com.finboxsolutions.easyrec.dashboard.model.PatternStat;
import com.finboxsolutions.easyrec.dashboard.model.PatternTrend;
import com.finboxsolutions.easyrec.dashboard.model.RowStats;
import com.finboxsolutions.easyrec.dashboard.model.RunContextRow;
import com.finboxsolutions.easyrec.dashboard.model.StatusLabel;
import com.finboxsolutions.easyrec.dashboard.service.CompareService;
import com.finboxsolutions.easyrec.dashboard.service.DashboardService;
import com.finboxsolutions.easyrec.dashboard.service.HistoryService;
import com.finboxsolutions.easyrec.dashboard.service.PatternService;

/** What each grid shows: its columns, their classes, and the value of each cell. */
class DashboardTableModelsTest {

    @Test
    @DisplayName("Batches: date, time, rate as a fraction, run cells, duration and description preview")
    void batchTable() {
        long millis = LocalDateTime.of(2026, 10, 1, 8, 5, 9).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        BatchRow batch = new BatchRow(7, "1.0", null, null, 1, millis, 90_000L, " month\n end ", 0);
        Map<String, String> cells = new LinkedHashMap<>();
        cells.put("Project", "/proj");
        DashboardService.BatchSummary summary = new DashboardService.BatchSummary(batch, 95.0d, 3, 1, 100L, 90L,
                List.of(), cells);
        BatchTableModel model = new BatchTableModel();
        model.setRows(List.of(summary));

        assertEquals(1, model.getRowCount());
        assertSame(summary, model.rowAt(0));
        assertEquals(7, value(model, 0, "Batch"));
        assertEquals("2026-10-01", value(model, 0, "Date"));
        assertEquals("08:05:09", value(model, 0, "Time"));
        assertEquals("-", value(model, 0, "User"));
        assertEquals(StatusLabel.PASSED, value(model, 0, "Status"));
        assertEquals(0.95d, (Double) value(model, 0, "Match Rate"), 1e-12);
        assertEquals("/proj", value(model, 0, "Project"));
        assertEquals("-", value(model, 0, "Template Path"), "a run cell the summary lacks");
        assertEquals("month end", value(model, 0, "Description"));
        assertEquals("1.5min", value(model, 0, "Duration"));
        assertEquals(100L, value(model, 0, DashboardTableModel.SOURCE_ROWS));
        assertEquals(Double.class, model.getColumnClass(column(model, "Match Rate")));
        assertEquals(StatusLabel.class, model.getColumnClass(column(model, "Status")));
        assertEquals(Long.class, model.getColumnClass(column(model, DashboardTableModel.TARGET_ROWS)));
        assertEquals("Duration", model.getColumnName(model.getColumnCount() - 1));
        for (String run : DashboardService.RUN_COLUMNS) {
            assertTrue(column(model, run) >= 0, run);
        }
        assertFalse(model.isCellEditable(0, 0));

        BatchRow bare = new BatchRow(8, null, null, "ops", null, null, null, null, 0);
        model.setRows(List.of(new DashboardService.BatchSummary(bare, null, 0, 0, 0L, 0L, List.of(), Map.of())));
        assertEquals(1, model.getRowCount(), "rows are replaced, not added");
        assertEquals("-", value(model, 0, "Date"));
        assertEquals("-", value(model, 0, "Duration"));
        assertNull(value(model, 0, "Match Rate"));
        assertEquals(StatusLabel.UNKNOWN, value(model, 0, "Status"));
    }

    @Test
    @DisplayName("Durations read in seconds, minutes or hours")
    void durations() {
        assertEquals("0.5s", duration(500L));
        assertEquals("59.9s", duration(59_900L));
        assertEquals("1.0min", duration(60_000L));
        assertEquals("2.0h", duration(7_200_000L));
        assertEquals("-", duration(0L));
    }

    @Test
    @DisplayName("A description preview collapses whitespace and is cut with an ellipsis")
    void preview() {
        assertEquals("-", BatchTableModel.preview(null));
        assertEquals("-", BatchTableModel.preview("  "));
        assertEquals("a b", BatchTableModel.preview(" a \t\n b "));
        String exact = "x".repeat(BatchTableModel.DESCRIPTION_PREVIEW_LENGTH);
        assertEquals(exact, BatchTableModel.preview(exact));
        String cut = BatchTableModel.preview("word ".repeat(20));
        assertEquals(BatchTableModel.DESCRIPTION_PREVIEW_LENGTH, cut.length());
        assertTrue(cut.endsWith("…"));
        assertFalse(cut.contains(" …"), "no space before the ellipsis");
    }

    @Test
    @DisplayName("Reconciliations: no statistics is no rows rather than zero, placeholders are a dash")
    void reconciliationTable() {
        RunContextRow context = new RunContextRow(1, 0, 10, null, null, "Murex", "<Undefined>", null, "FX", null,
                null, null, null, null, null, null, 0, null, null);
        RowStats stats = new RowStats(1, 10, 1, 100, 80, 0, 0, 10, 90, 0, 0, null, null, null);
        ReconciliationTableModel model = new ReconciliationTableModel();
        model.setRows(List.of(
                new DashboardService.Reconciliation(1, 10, 10, "/rec/a.xml", context, stats),
                new DashboardService.Reconciliation(1, 11, 11, null, context, null)));

        assertEquals(10, value(model, 0, "Template ID"));
        assertEquals("/rec/a.xml", value(model, 0, "Template Path"));
        assertEquals(StatusLabel.FAILED, value(model, 0, "Status"));
        assertEquals(0.9d, (Double) value(model, 0, "Match Rate"), 1e-12);
        assertEquals("Murex", value(model, 0, "Source"));
        assertEquals("-", value(model, 0, "Target"));
        assertEquals("FX", value(model, 0, "Category 1"));
        assertEquals("-", value(model, 0, "Category 2"));
        assertEquals(100L, value(model, 0, DashboardTableModel.SOURCE_ROWS));
        assertEquals(80L, value(model, 0, DashboardTableModel.TARGET_ROWS));

        assertEquals("-", value(model, 1, "Template Path"));
        assertNull(value(model, 1, DashboardTableModel.SOURCE_ROWS));
        assertNull(value(model, 1, "Match Rate"));
        assertEquals(StatusLabel.UNKNOWN, value(model, 1, "Status"));
        assertEquals(-1, column(model, "Project"), "one batch, one project: no column for it");
    }

    @Test
    @DisplayName("Comparison: one column per batch, absent cells told apart from empty ones")
    void compareTable() {
        CompareService.Cell full = new CompareService.Cell(90.0d, 90, 10, 1, null);
        CompareService.Cell empty = new CompareService.Cell(null, 0, 0, 1, null);
        CompareService.CompareRow row = new CompareService.CompareRow("/rec/a.xml",
                Arrays.asList(full, null, empty), 0.0d, null, null, null, true);
        CompareTableModel model = new CompareTableModel();
        model.setRows("Column", List.of(4, 5, 6), List.of(row), CompareTableModel.CellMeasure.MATCH_PERCENTAGE);

        assertEquals(List.of("Column", "Batch 4", "Batch 5", "Batch 6", "Spread", "Delta"), columnNames(model));
        assertEquals("Column", model.labelColumn());
        assertEquals(CompareTableModel.CellMeasure.MATCH_PERCENTAGE, model.measure());
        assertEquals("/rec/a.xml", model.getValueAt(0, 0));
        assertEquals(90.0d, model.getValueAt(0, 1));
        assertNull(model.getValueAt(0, 2));
        assertNull(model.getValueAt(0, 3));
        assertTrue(model.isAbsent(0, 2), "batch 5 has no such row");
        assertFalse(model.isAbsent(0, 3), "batch 6 has it, with no value");
        assertFalse(model.isAbsent(0, 0));
        assertEquals(5, model.batchAt(2));
        assertNull(model.batchAt(0));
        assertNull(model.batchAt(4));
        assertEquals(0.0d, model.getValueAt(0, 4));
        assertEquals(String.class, model.getColumnClass(0));
        assertEquals(Double.class, model.getColumnClass(1));
    }

    @Test
    @DisplayName("Column statistics: the ratio is handed over as it is stored")
    void columnStatsTable() {
        ColumnStats stats = new ColumnStats(1, 10, "AMOUNT", null, "0.01", 0.875d, 5, 30, 4, 1, -2.5d, 2.5d,
                null, null, null, null, null, null);
        ColumnStatsTableModel model = new ColumnStatsTableModel();
        model.setRows(List.of(stats));
        assertEquals("AMOUNT", value(model, 0, "Column"));
        assertEquals("-", value(model, 0, "Class"));
        assertEquals("0.01", value(model, 0, "Tolerance"));
        assertEquals(0.875d, value(model, 0, "Match %"));
        assertEquals(5L, value(model, 0, "Unmatched"));
        assertEquals(4L, value(model, 0, "In Tolerance"));
        assertEquals(-2.5d, value(model, 0, "Impact"));
        assertNull(value(model, 0, "Std Dev"));
        assertEquals(Long.class, model.getColumnClass(column(model, "Forced")));
        assertEquals(Double.class, model.getColumnClass(column(model, "Max Diff %")));
    }

    @Test
    @DisplayName("History: the current execution is flagged, the rate handed over as a fraction")
    void historyTable() {
        BatchRow batch = new BatchRow(3, null, null, null, 1, null, null, null, 0);
        HistoryService.Execution execution = new HistoryService.Execution(batch, List.of(3), 2, 90, 90.0d, 10L,
                1, 1, null, "Batch 3", "#3", -1.5d, 4L, true);
        HistoryTableModel model = new HistoryTableModel();
        model.setRows(List.of(execution));
        assertEquals("Batch 3", value(model, 0, "When"));
        assertEquals(3, value(model, 0, "Batch"));
        assertEquals(0.9d, (Double) value(model, 0, "Match Rate"), 1e-12);
        assertEquals(-1.5d, value(model, 0, "Rate Change"));
        assertEquals(4L, value(model, 0, "Breaks Change"));
        assertTrue(model.isCurrent(0));
        assertEquals(Long.class, model.getColumnClass(column(model, "Breaks")));
    }

    @Test
    @DisplayName("Patterns, their sightings and their history")
    void patternTables() {
        PatternRow pattern = new PatternRow(10, 1, "aaa", "NPV / No rule found", 2, 9);
        PatternStat stat = new PatternStat(5, pattern, 8, true, null, 200L, 100L);
        PatternService.PatternLine line = new PatternService.PatternLine(stat, PatternTrend.INCREASING, 5L, 3L,
                4.0d, false, null);

        PatternTableModel patterns = new PatternTableModel();
        patterns.setRows(List.of(line));
        assertEquals("NPV / No rule found", value(patterns, 0, "Pattern"));
        assertEquals(PatternTrend.INCREASING, value(patterns, 0, "Trend"));
        assertEquals(8L, value(patterns, 0, "Occurrences"));
        assertEquals(3L, value(patterns, 0, "Change"));
        assertEquals(5L, value(patterns, 0, "Previous"));
        assertNull(value(patterns, 0, "Column Unexplained"));
        assertEquals(2, value(patterns, 0, "First Seen Run"));
        assertEquals(9, value(patterns, 0, "Linked To"));
        assertEquals(PatternTrend.class, patterns.getColumnClass(column(patterns, "Trend")));

        PatternSightingTableModel sightings = new PatternSightingTableModel();
        sightings.setRows(List.of(new PatternSighting(pattern, "/rec/a.xml", null)));
        assertEquals("/rec/a.xml", value(sightings, 0, "Template Path"));
        assertEquals(1, value(sightings, 0, "Template"));
        assertEquals(10, value(sightings, 0, "Pattern"));
        assertNull(value(sightings, 0, "Occurrences"), "no stat row is no count");
        assertEquals(2, value(sightings, 0, "First Seen Run"));

        PatternHistoryTableModel history = new PatternHistoryTableModel();
        history.setRows(List.of(new PatternService.PatternPoint(line, null, 1, null, "Run 5", "#5")));
        assertEquals("Run 5", value(history, 0, "When"));
        assertNull(value(history, 0, "Batch"));
        assertEquals(5, value(history, 0, "Run"));
        assertEquals(10, value(history, 0, "Pattern"));
        assertEquals(4.0d, value(history, 0, "% of Rows"));
        assertEquals(200L, value(history, 0, "Rows Compared"));
    }

    // ---------------------------------------------------------------------------- helpers

    private static String duration(long millis) {
        BatchRow batch = new BatchRow(1, null, null, null, 1, null, millis, null, 0);
        BatchTableModel model = new BatchTableModel();
        model.setRows(List.of(new DashboardService.BatchSummary(batch, null, 0, 0, 0L, 0L, List.of(), Map.of())));
        return (String) value(model, 0, "Duration");
    }

    private static Object value(TableModel model, int row, String column) {
        int index = column(model, column);
        if (index < 0) {
            throw new AssertionError("no column " + column + " in " + columnNames(model));
        }
        return model.getValueAt(row, index);
    }

    private static int column(TableModel model, String name) {
        return columnNames(model).indexOf(name);
    }

    private static List<String> columnNames(TableModel model) {
        List<String> names = new ArrayList<>();
        for (int index = 0; index < model.getColumnCount(); index++) {
            names.add(model.getColumnName(index));
        }
        return names;
    }
}
