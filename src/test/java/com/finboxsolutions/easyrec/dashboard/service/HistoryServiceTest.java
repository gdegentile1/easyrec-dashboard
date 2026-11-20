package com.finboxsolutions.easyrec.dashboard.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.finboxsolutions.easyrec.dashboard.TestDatabase;
import com.finboxsolutions.easyrec.dashboard.dao.DashboardDao;
import com.finboxsolutions.easyrec.dashboard.service.HistoryService.Execution;
import com.finboxsolutions.easyrec.dashboard.service.HistoryService.History;

/** The trend of a batch's templates, and of one template, across executions. */
class HistoryServiceTest {

    private HistoryService history;

    /**
     * Four batches, one run each, ids in date order. Batches 1, 2 and 4 reconciled a.xml and
     * b.xml (2 also c.xml), batch 3 only a.xml.
     *
     * <pre>
     *            a.xml          b.xml           c.xml
     * batch 1    90/100 failed  100/100 passed
     * batch 2    95/100 failed  100/100 passed  0/100 failed
     * batch 3    50/100 failed
     * batch 4    80/100 failed  100/100 passed
     * </pre>
     */
    @BeforeEach
    void setUp() {
        TestDatabase database = TestDatabase.create()
                .template(1, "/p/project.xml")
                .template(10, "/rec/a.xml")
                .template(11, "/rec/b.xml")
                .template(12, "/rec/c.xml");
        for (int id = 1; id <= 4; id++) {
            database.batch(id, "2026-10-0" + id + " 08:00:00").run(id, id, 1);
        }
        database.context(1, 0, 10).context(1, 1, 11)
                .context(2, 0, 10).context(2, 1, 11).context(2, 2, 12)
                .context(3, 0, 10)
                .context(4, 0, 10).context(4, 1, 11)
                .rowStats(1, 10, 1, 100, 100, 90).rowStats(1, 11, 0, 100, 100, 100)
                .rowStats(2, 10, 1, 100, 100, 95).rowStats(2, 11, 0, 100, 100, 100).rowStats(2, 12, 1, 100, 100, 0)
                .rowStats(3, 10, 1, 100, 100, 50)
                .rowStats(4, 10, 1, 100, 100, 80).rowStats(4, 11, 0, 100, 100, 100);
        DashboardDao dao = database.dao();
        history = new HistoryService(dao, new DashboardService(dao));
    }

    @Test
    @DisplayName("A batch is compared with the batches that ran all its templates, on those templates only")
    void batchHistory() {
        History found = history.batchHistory(4);
        assertEquals(List.of("/rec/a.xml", "/rec/b.xml"), found.templatePaths());
        assertEquals(List.of(1, 2, 4), batchIds(found.executions()), "batch 3 did not run b.xml");

        Execution first = found.executions().get(0);
        assertEquals(95.0d, first.matchRate());
        assertEquals(10L, first.breaks());
        assertEquals(1, first.passed());
        assertEquals(1, first.failed());
        assertEquals(2, first.reconciliations());
        assertEquals(List.of(1), first.runIds());
        assertNull(first.rateDelta(), "nothing before the first");
        assertNull(first.breaksDelta());
        assertEquals("2026-10-01 08:00", first.longLabel());
        assertEquals(DateTimeFormatter.ofPattern("dd MMM").format(LocalDateTime.of(2026, 10, 1, 8, 0)),
                first.shortLabel());

        Execution second = found.executions().get(1);
        assertEquals(97.5d, second.matchRate(), 1e-9, "c.xml is not part of this trend");
        assertEquals(2.5d, second.rateDelta(), 1e-9);
        assertEquals(-5L, second.breaksDelta());

        Execution latest = found.latest();
        assertEquals(4, latest.batch().batchId());
        assertTrue(latest.current());
        assertFalse(first.current());
        assertEquals(90.0d, latest.matchRate());
        assertEquals(-7.5d, latest.rateDelta(), 1e-9);
        assertEquals(15L, latest.breaksDelta());
        assertEquals(Rates.Tone.WARN, latest.tone());

        assertEquals(97.5d, found.bestRate(), 1e-9);
        assertEquals(90.0d, found.worstRate(), 1e-9);
        assertEquals(List.of(4, 2, 1), batchIds(found.newestFirst()));
    }

    @Test
    @DisplayName("The reference batch is always part of its own history")
    void narrowerReference() {
        History found = history.batchHistory(3);
        assertEquals(List.of("/rec/a.xml"), found.templatePaths());
        assertEquals(List.of(1, 2, 3, 4), batchIds(found.executions()));
        assertEquals(50.0d, found.executions().get(2).matchRate());
        assertTrue(found.executions().get(2).current());
    }

    @Test
    @DisplayName("A batch with no run has no history")
    void unknownBatch() {
        History found = history.batchHistory(99);
        assertTrue(found.executions().isEmpty());
        assertNull(found.latest());
        assertNull(found.bestRate());
    }

    @Test
    @DisplayName("A template's history is every batch that ran it")
    void templateHistory() {
        History found = history.templateHistory(11);
        assertEquals(List.of(1, 2, 4), batchIds(found.executions()));
        assertTrue(found.executions().stream().allMatch(execution -> execution.matchRate() == 100.0d));
        assertTrue(found.executions().stream().noneMatch(Execution::current));
        assertEquals(0.0d, found.latest().rateDelta());

        assertTrue(history.templateHistory(99).executions().isEmpty());
    }

    private static List<Integer> batchIds(List<Execution> executions) {
        return executions.stream().map(execution -> execution.batch().batchId()).toList();
    }
}
