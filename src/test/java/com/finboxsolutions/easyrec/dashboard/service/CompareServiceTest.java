package com.finboxsolutions.easyrec.dashboard.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.finboxsolutions.easyrec.dashboard.TestDatabase;
import com.finboxsolutions.easyrec.dashboard.dao.DashboardDao;
import com.finboxsolutions.easyrec.dashboard.model.BatchRow;
import com.finboxsolutions.easyrec.dashboard.service.CompareService.BatchTotals;
import com.finboxsolutions.easyrec.dashboard.service.CompareService.Cell;
import com.finboxsolutions.easyrec.dashboard.service.CompareService.CompareRow;
import com.finboxsolutions.easyrec.dashboard.service.DashboardService.Reconciliation;

/** Batches side by side: per template, then per column of one template. */
class CompareServiceTest {

    private static final List<Integer> BATCHES = List.of(1, 2, 3);

    private CompareService compare;
    private Map<Integer, List<Reconciliation>> byBatch;

    /**
     * Batch 1 reconciled a.xml (90 of 100) and b.xml (50 of 100), batch 2 a.xml (70 of 100),
     * batch 3 a.xml with no statistics.
     */
    @BeforeEach
    void setUp() {
        TestDatabase database = TestDatabase.create()
                .template(1, "/p/project.xml")
                .template(10, "/rec/a.xml")
                .template(11, "/rec/b.xml");
        for (int id : BATCHES) {
            database.batch(id, "2026-10-0" + id + " 08:00:00").run(id, id, 1).context(id, 0, 10);
        }
        database.context(1, 1, 11)
                .rowStats(1, 10, 1, 100, 100, 90)
                .rowStats(1, 11, 1, 100, 100, 50)
                .rowStats(2, 10, 1, 100, 100, 70)
                .columnStats(1, 10, "AMOUNT", "Double", 0.9d, 10, 90)
                .columnStats(1, 10, "CCY", "String", 1.0d, 0, 100)
                .columnStats(2, 10, "AMOUNT", "Double", 0.5d, 50, 50);
        DashboardDao dao = database.dao();
        DashboardService dashboard = new DashboardService(dao);
        compare = new CompareService(dao, dashboard);
        byBatch = new LinkedHashMap<>();
        for (int id : BATCHES) {
            byBatch.put(id, dashboard.findReconciliations(List.of(id), null));
        }
    }

    @Test
    @DisplayName("The selection keeps the known batches, in id order, six at most")
    void resolveSelection() {
        List<BatchRow> all = new ArrayList<>();
        IntStream.rangeClosed(1, 8).forEach(id -> all.add(new BatchRow(id, null, null, null, 1, null, null, null, 0)));
        List<BatchRow> selected = compare.resolveSelection(all, List.of(8, 3, 1, 2, 4, 5, 6, 7, 99));
        assertEquals(List.of(1, 2, 3, 4, 5, 6), selected.stream().map(BatchRow::batchId).toList());
        assertTrue(compare.resolveSelection(all, List.of(99)).isEmpty());
    }

    @Test
    @DisplayName("Templates: one cell per batch, absent where a batch did not reconcile it, biggest spread first")
    void compareTemplates() {
        List<CompareRow> rows = compare.compareTemplates(BATCHES, byBatch);
        assertEquals(List.of("/rec/a.xml", "/rec/b.xml"), rows.stream().map(CompareRow::label).toList());

        CompareRow a = rows.get(0);
        assertEquals(90.0d, a.cells().get(0).value());
        assertEquals(10L, a.cells().get(0).breaks());
        assertEquals(70.0d, a.cells().get(1).value());
        Cell silent = a.cells().get(2);
        assertNull(silent.value(), "reconciled, but no statistics");
        assertEquals(1, silent.count());
        assertEquals(20.0d, a.spread(), 1e-9);
        assertEquals(-20.0d, a.delta(), 1e-9);
        assertEquals(1, a.deltaFromBatchId());
        assertEquals(2, a.deltaToBatchId());
        assertFalse(a.missingFromSome());

        CompareRow b = rows.get(1);
        assertEquals(50.0d, b.cells().get(0).value());
        assertNull(b.cells().get(1), "batch 2 did not reconcile b.xml");
        assertNull(b.spread(), "one value: no spread");
        assertNull(b.delta());
        assertNull(b.deltaFromBatchId());
        assertTrue(b.missingFromSome());
    }

    @Test
    @DisplayName("Columns of one template: the match percentage of each column per batch")
    void compareColumns() {
        List<CompareRow> rows = compare.compareColumns(BATCHES, byBatch, "/rec/a.xml");
        assertEquals(List.of("AMOUNT", "CCY"), rows.stream().map(CompareRow::label).toList());

        CompareRow amount = rows.get(0);
        assertEquals(90.0d, amount.cells().get(0).value(), 1e-9);
        assertEquals(90L, amount.cells().get(0).matched());
        assertEquals(10L, amount.cells().get(0).breaks());
        assertEquals("Double", amount.cells().get(0).detail());
        assertEquals(50.0d, amount.cells().get(1).value(), 1e-9);
        assertNull(amount.cells().get(2), "batch 3 recorded no column statistics");
        assertEquals(40.0d, amount.spread(), 1e-9);
        assertEquals(-40.0d, amount.delta(), 1e-9);
        assertTrue(amount.missingFromSome());

        CompareRow ccy = rows.get(1);
        assertEquals(100.0d, ccy.cells().get(0).value(), 1e-9);
        assertNull(ccy.spread());

        assertTrue(compare.compareColumns(BATCHES, byBatch, "/rec/none.xml").isEmpty());
    }

    @Test
    @DisplayName("Batch totals add up the reconciliations, a statistics row shared by two counted once")
    void totals() {
        Map<Integer, BatchTotals> totals = compare.totals(BATCHES, byBatch);
        BatchTotals first = totals.get(1);
        assertEquals(140L, first.matched());
        assertEquals(60L, first.unmatched());
        assertEquals(60L, first.breaks());
        assertEquals(200L, first.rowsSource());
        assertEquals(70.0d, first.matchRate());
        assertEquals(1, first.runCount());
        assertEquals(2, first.templateCount());

        BatchTotals empty = totals.get(3);
        assertEquals(0L, empty.matched());
        assertNull(empty.matchRate());
        assertEquals(1, empty.templateCount());

        Reconciliation rec = byBatch.get(2).get(0);
        Reconciliation twin = new Reconciliation(rec.runId(), 99, rec.statsTemplateId(), "/rec/twin.xml",
                rec.context(), rec.stats());
        BatchTotals shared = compare.totals(List.of(2), Map.of(2, List.of(rec, twin))).get(2);
        assertEquals(70L, shared.matched(), "the same statistics row is not counted twice");
        assertEquals(2, shared.templateCount());
    }
}
