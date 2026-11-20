package com.finboxsolutions.easyrec.dashboard.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.finboxsolutions.easyrec.dashboard.TestDatabase;
import com.finboxsolutions.easyrec.dashboard.dao.JdbcDashboardDao.DashboardDataException;
import com.finboxsolutions.easyrec.dashboard.dao.JdbcDashboardDao.DashboardSchemaMissingException;
import com.finboxsolutions.easyrec.dashboard.model.BatchRow;
import com.finboxsolutions.easyrec.dashboard.model.ColumnStats;
import com.finboxsolutions.easyrec.dashboard.model.PivotMetric;
import com.finboxsolutions.easyrec.dashboard.model.PivotRow;
import com.finboxsolutions.easyrec.dashboard.model.RowStats;
import com.finboxsolutions.easyrec.dashboard.model.RunContextRow;
import com.finboxsolutions.easyrec.dashboard.model.RunRow;
import com.finboxsolutions.easyrec.dashboard.model.StatusLabel;

/** The DAO against the real dashboard DDL on H2. */
class JdbcDashboardDaoTest {

    private TestDatabase database;
    private JdbcDashboardDao dao;

    /**
     * Template 1 is the project, 10 and 11 the reconciliations. Batch 1 ran run 1 over both,
     * batch 2 ran run 2 over 10, batch 3 has no run.
     */
    @BeforeEach
    void setUp() {
        database = TestDatabase.create()
                .template(1, "/projects/trades.xml")
                .template(10, "/rec/Trades.xml")
                .template(11, "/rec/cash.xml")
                .batch(1, "2026-10-01 08:30:00", 1, "ops")
                .batch(2, "2026-10-02 09:00:00", 0, " Alice ")
                .batch(3, null, -1, "ops ")
                .run(1, 1, 1)
                .run(2, 2, 1)
                .context(1, 0, 10)
                .context(1, 1, 11)
                .context(2, 0, 10)
                .rowStats(1, 10, 0, 100, 90, 0, 0, 0, 100)
                .rowStats(1, 11, 1, 50, 60, 2, 3, 5, 50)
                .rowStats(2, 10, 1, 100, 100, 80);
        dao = database.dao();
    }

    @Test
    @DisplayName("Batches come newest id first, with their timestamp read from SYS_TIME")
    void batches() {
        List<BatchRow> batches = dao.findAllBatches();
        assertEquals(List.of(3, 2, 1), batches.stream().map(BatchRow::batchId).toList());
        BatchRow first = dao.findBatch(1);
        assertEquals(LocalDateTime.of(2026, 10, 1, 8, 30), first.when());
        assertEquals(StatusLabel.PASSED, first.status());
        assertEquals(StatusLabel.FAILED, dao.findBatch(2).status());
        assertEquals(StatusLabel.ERROR, dao.findBatch(3).status());
        assertNull(dao.findBatch(3).epochMillis(), "no SYS_TIME, no SYS_DATE");
        assertNull(dao.findBatch(99));
    }

    @Test
    @DisplayName("Runs are grouped by batch, a requested batch with no run included")
    void runs() {
        Map<Integer, List<RunRow>> byBatch = dao.findRunsByBatch(List.of(1, 3));
        assertEquals(List.of(1, 3), List.copyOf(byBatch.keySet()));
        assertEquals(1, byBatch.get(1).size());
        assertTrue(byBatch.get(3).isEmpty());
        assertEquals(1, dao.findRun(2).templateId());
        assertNull(dao.findRun(99));
        assertEquals(List.of(1, 2), List.copyOf(dao.findRuns(List.of(2, 1, 99)).keySet()));
        assertTrue(dao.findRunsByBatch(List.of()).isEmpty());
    }

    @Test
    @DisplayName("Contexts and row statistics come ordered by run then template")
    void contextsAndRowStats() {
        List<RunContextRow> contexts = dao.findRunContexts(List.of(2, 1));
        assertEquals(List.of(10, 11, 10), contexts.stream().map(RunContextRow::templateId).toList());
        List<RowStats> stats = dao.findRowStats(List.of(1, 2));
        assertEquals(3, stats.size());
        RowStats cash = stats.get(1);
        assertEquals(11, cash.templateId());
        assertEquals(StatusLabel.FAILED, cash.status());
        assertEquals(10L, cash.breakCount());
        assertEquals(60L, cash.totalRows());
        assertTrue(dao.findRowStats(List.of()).isEmpty());
    }

    @Test
    @DisplayName("Column statistics: most unmatched first, or grouped by run for several pairs")
    void columnStats() {
        database.columnStats(1, 10, "CCY", "String", 1.0d, 0, 100)
                .columnStats(1, 10, "AMOUNT", "Double", 0.9d, 10, 90)
                .columnStats(2, 10, "AMOUNT", "Double", 0.5d, 50, 50)
                .columnStats(2, 11, "OTHER", "Double", 0.5d, 50, 50);
        assertEquals(List.of("AMOUNT", "CCY"),
                dao.findColumnStats(1, 10).stream().map(ColumnStats::colLabel).toList());

        Map<Integer, Integer> pairs = new LinkedHashMap<>();
        pairs.put(1, 10);
        pairs.put(2, 10);
        Map<Integer, List<ColumnStats>> byRun = dao.findColumnStats(pairs);
        assertEquals(2, byRun.get(1).size());
        assertEquals(List.of("AMOUNT"), byRun.get(2).stream().map(ColumnStats::colLabel).toList(),
                "run 2's template 11 was not asked for");
        assertTrue(dao.findColumnStats(Map.of()).isEmpty());
    }

    @Test
    @DisplayName("Pivot rows read the split keys, blank as empty, and get their totals")
    void pivots() {
        database.exec("INSERT INTO ER_DASHBOARD_PIVOT (RUN_ID, TEMPLATE_ID, PIVOT_KEYS, PIVOT_LEVEL, PIVOT_KEY0, "
                + "PIVOT_KEY1, MISSING_SRC_COUNT, UNMATCH_COUNT, UNMATCH_IMPACT_ABS, MISSING_TRG_IMPACT_ABS) "
                + "VALUES (1, 10, 'EUR/B1', 1, 'EUR', 'B1', 2, 3, 4.5, 1.5)");
        database.exec("INSERT INTO ER_DASHBOARD_PIVOT (RUN_ID, TEMPLATE_ID, PIVOT_KEYS, PIVOT_LEVEL, PIVOT_KEY0) "
                + "VALUES (1, 10, 'AUD', 0, 'AUD')");
        List<PivotRow> pivots = dao.findPivots(1, 10);
        assertEquals(List.of("AUD", "EUR/B1"), pivots.stream().map(PivotRow::pivotKeys).toList());
        PivotRow eur = pivots.get(1);
        assertEquals(PivotRow.MAX_KEYS, eur.keys().size());
        assertEquals(List.of("EUR", "B1", ""), eur.keys().subList(0, 3));
        assertEquals(5.0d, eur.metric(PivotMetric.TOTAL_BREAKS));
        assertEquals(6.0d, eur.metric(PivotMetric.TOTAL_IMPACT_ABS));
        assertEquals(0.0d, pivots.get(0).metric(PivotMetric.UNMATCH_COUNT), "a null column reads as zero");
        assertTrue(dao.findPivots(2, 10).isEmpty());
    }

    @Test
    @DisplayName("Templates by id, by a case-insensitive path fragment and by exact paths")
    void templates() {
        assertEquals(List.of(10, 11), List.copyOf(dao.findTemplates(List.of(10, 11, 99)).keySet()));
        assertEquals("/rec/cash.xml", dao.findTemplate(11).fullPath());
        assertNull(dao.findTemplate(99));
        assertEquals(List.of(10), dao.findTemplateIdsByPathFragment("REC/TRADES"));
        assertEquals(3, dao.findTemplateIdsByPathFragment("").size(), "an empty fragment matches every path");
        Map<Integer, String> byPath = dao.findTemplateIdsByPaths(List.of("/rec/cash.xml", "/rec/cash.xml", "/nope"));
        assertEquals(Map.of(11, "/rec/cash.xml"), byPath);
    }

    @Test
    @DisplayName("Filter values are distinct, trimmed, blank-free and sorted case-insensitively")
    void filterValues() {
        database.exec("UPDATE ER_DASHBOARD_RUN_CONTEXT SET NAME = 'beta' WHERE RUN_ID = 1 AND POSITION = 0")
                .exec("UPDATE ER_DASHBOARD_RUN_CONTEXT SET NAME = ' Alpha ' WHERE RUN_ID = 1 AND POSITION = 1")
                .exec("UPDATE ER_DASHBOARD_RUN_CONTEXT SET NAME = '  ' WHERE RUN_ID = 2");
        assertEquals(List.of("Alpha", "beta"), dao.findContextValues("name"));
        assertEquals(List.of("Alice", "ops"), dao.findBatchUserNames());
    }

    @Test
    @DisplayName("Only the fixed filter columns may reach the statement text")
    void filterColumnsAreChecked() {
        assertThrows(IllegalArgumentException.class, () -> dao.findContextValues("DESCRIPTION"));
        assertThrows(IllegalArgumentException.class,
                () -> dao.findContextValues("NAME FROM ER_DASHBOARD_RUN_CONTEXT; DROP TABLE ER_DASHBOARD_BATCH; --"));
    }

    @Test
    @DisplayName("Writes: the batch description, the project path of runs, one context row")
    void writes() {
        assertEquals(1, dao.updateBatchDescription(1, "month end"));
        assertEquals("month end", dao.findBatch(1).description());
        assertEquals(0, dao.updateBatchDescription(99, "x"));

        assertEquals(2, dao.updateRunProjectPath(List.of(1, 2), "/projects/new.xml"));
        assertEquals("/projects/new.xml", dao.findRun(2).projectPath());

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("category1", "FX");
        values.put("dueDate", LocalDate.of(2026, 12, 31));
        values.put("statusCode", 1);
        assertEquals(1, dao.updateRunContext(1, 11, values));
        List<RunContextRow> contexts = dao.findRunContexts(List.of(1));
        RunContextRow other = contexts.get(0);
        RunContextRow updated = contexts.get(1);
        assertEquals("FX", updated.category1());
        assertEquals(LocalDate.of(2026, 12, 31), updated.dueDate());
        assertEquals(StatusLabel.FAILED, updated.status());
        assertNull(other.category1(), "the other context row of the run is untouched");

        assertEquals(0, dao.updateRunContext(1, 11, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> dao.updateRunContext(1, 11, Map.of("name", "x")),
                "NAME is EasyRec's, not the operator's");
    }

    @Test
    @DisplayName("Deleting a batch removes its runs and every row keyed to them, children first")
    void deleteBatches() {
        database.columnStats(1, 10, "AMOUNT", "Double", 0.9d, 10, 90)
                .exec("INSERT INTO ER_DASHBOARD_PIVOT (RUN_ID, TEMPLATE_ID, PIVOT_KEYS) VALUES (1, 10, 'EUR')");
        Map<String, Integer> removed = dao.deleteBatches(List.of(1, 3));
        assertEquals(1, removed.get("ER_DASHBOARD_PIVOT"));
        assertEquals(1, removed.get("ER_DASHBOARD_STAT_COLS"));
        assertEquals(2, removed.get("ER_DASHBOARD_STAT_ROWS"));
        assertEquals(2, removed.get("ER_DASHBOARD_RUN_CONTEXT"));
        assertEquals(1, removed.get("ER_DASHBOARD_RUN"));
        assertEquals(2, removed.get("ER_DASHBOARD_BATCH"));
        assertEquals(0, removed.get("ER_DASHBOARD_PATTERN_STAT"));
        assertEquals(List.of(2), dao.findAllBatches().stream().map(BatchRow::batchId).toList());
        assertEquals(1, dao.findRowStats(List.of(2)).size(), "the other batch's statistics are kept");
        assertTrue(dao.deleteBatches(List.of()).isEmpty());
    }

    @Test
    @DisplayName("A datasource without the dashboard tables raises the schema-missing failure")
    void schemaMissing() {
        JdbcDashboardDao bare = TestDatabase.empty().dao();
        assertThrows(DashboardSchemaMissingException.class, bare::findAllBatches);
        assertThrows(DashboardSchemaMissingException.class, () -> bare.updateBatchDescription(1, "x"));
        DashboardDataException other = assertThrows(DashboardDataException.class,
                () -> dao.updateRunContext(1, 10, Map.of("statusCode", "not a number")));
        assertFalse(other instanceof DashboardSchemaMissingException, "a bad value is not a missing table");
    }

    @Test
    @DisplayName("A missing table is recognised for H2, Oracle and DuckDB, also as a cause")
    void isMissingTable() {
        assertTrue(JdbcDashboardDao.isMissingTable(new SQLException("x", "42S02")));
        assertTrue(JdbcDashboardDao.isMissingTable(new SQLException("x", "42S02", 0)));
        assertTrue(JdbcDashboardDao.isMissingTable(new SQLException("x", null, 42102)));
        assertTrue(JdbcDashboardDao.isMissingTable(new SQLException("empty database", "42S04", 42104)));
        assertTrue(JdbcDashboardDao.isMissingTable(new SQLException("similar names", "42S03", 42103)));
        assertTrue(JdbcDashboardDao.isMissingTable(new SQLException("ORA-00942", "42000", 942)));
        assertTrue(JdbcDashboardDao.isMissingTable(
                new SQLException("Catalog Error: Table with name ER_DASHBOARD_BATCH does not exist!")));
        assertTrue(JdbcDashboardDao.isMissingTable(
                new SQLException("wrapper", new SQLException("x", "42S02"))));
        assertFalse(JdbcDashboardDao.isMissingTable(new SQLException("syntax", "42000", 900)));
        assertFalse(JdbcDashboardDao.isMissingTable(new SQLException((String) null)));
    }

    @Test
    @DisplayName("The H2 timestamp is the local wall-clock time")
    void timestampIsLocal() {
        long expected = LocalDateTime.of(2026, 10, 2, 9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        assertEquals(expected, dao.findBatch(2).epochMillis());
    }
}
