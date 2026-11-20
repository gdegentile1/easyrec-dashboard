package com.finboxsolutions.easyrec.dashboard.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.finboxsolutions.easyrec.dashboard.TestDatabase;
import com.finboxsolutions.easyrec.dashboard.model.BatchRow;
import com.finboxsolutions.easyrec.dashboard.model.StatusLabel;
import com.finboxsolutions.easyrec.dashboard.service.DashboardService.BatchSummary;
import com.finboxsolutions.easyrec.dashboard.service.DashboardService.PeriodCard;
import com.finboxsolutions.easyrec.dashboard.service.DashboardService.Reconciliation;

/** The aggregations the home, batch list and batch screens share. */
class DashboardServiceTest {

    private DashboardService service;

    /**
     * Batch 1 ran run 1 over templates 10 (passed, 80 of 100) and 11 (failed, 30 of 60).
     * Batch 2 has no run. Batch 3 ran run 3 over template 20, whose statistics an older
     * engine filed under 21, and over template 11, which recorded no statistics.
     */
    @BeforeEach
    void setUp() {
        TestDatabase database = TestDatabase.create()
                .template(1, "/p/project.xml")
                .template(10, "/rec/a.xml")
                .template(11, "/rec/b.xml")
                .template(20, "/rec/legacy.xml")
                .template(21, "/rec/legacy.xml#stats")
                .batch(1, "2026-10-01 08:00:00", 1, "ops")
                .batch(2, "2026-10-02 08:00:00", 1, "<Undefined>")
                .batch(3, "2026-10-03 08:00:00", 1, "zed")
                .run(1, 1, 1)
                .run(3, 3, 1)
                .context(1, 0, 10)
                .context(1, 1, 11)
                .context(3, 0, 20)
                .context(3, 1, 11)
                .rowStats(1, 10, 0, 100, 90, 80)
                .rowStats(1, 11, 1, 50, 60, 30)
                .rowStats(3, 21, 0, 10, 10, 10)
                .exec("UPDATE ER_DASHBOARD_RUN SET PROJECT_PATH = '/proj', SOURCE_ALIAS = 'SRC', "
                        + "SOURCE_LABEL = '<Undefined>' WHERE RUN_ID = 1")
                .exec("UPDATE ER_DASHBOARD_RUN_CONTEXT SET NAME = 'Proj B', SOURCE_LABEL = 'Murex' "
                        + "WHERE RUN_ID = 1 AND POSITION = 0")
                .exec("UPDATE ER_DASHBOARD_RUN_CONTEXT SET NAME = 'proj a', SOURCE_LABEL = '<Undefined>' "
                        + "WHERE RUN_ID = 1 AND POSITION = 1")
                .exec("UPDATE ER_DASHBOARD_RUN_CONTEXT SET NAME = '<Undefined>' WHERE RUN_ID = 3");
        service = new DashboardService(database.dao());
    }

    // ---------------------------------------------------------------------- date windows

    @Test
    @DisplayName("A date range covers both whole days, and a batch with no date is never in one")
    void batchesInRange() {
        DashboardService utc = new DashboardService(null, ZoneOffset.UTC);
        List<BatchRow> batches = List.of(
                batchAt(1, "2026-10-01T00:00:00"),
                batchAt(2, "2026-10-01T23:59:59"),
                batchAt(3, "2026-10-02T00:00:00"),
                batchAt(4, null));
        assertEquals(List.of(1, 2), ids(utc.findBatchesInRange(batches, LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 1))));
        assertEquals(List.of(1, 2, 3), ids(utc.findBatchesInRange(batches, null, null)), "open ends");
        assertEquals(List.of(3), ids(utc.findBatchesInRange(batches, LocalDate.of(2026, 10, 2), null)));
    }

    @Test
    @DisplayName("The period cards overlap, count their batches and add up their pass and fail counts")
    void periodCards() {
        DashboardService utc = new DashboardService(null, ZoneOffset.UTC);
        List<BatchRow> batches = List.of(
                batchAt(1, "2026-10-07T12:00:00"),
                batchAt(2, "2026-10-02T12:00:00"),
                batchAt(3, "2026-09-08T12:00:00"),
                batchAt(4, "2026-09-07T12:00:00"));
        Map<Integer, int[]> counts = Map.of(1, new int[] {2, 1}, 2, new int[] {1, 0}, 3, new int[] {0, 4});
        List<PeriodCard> cards = utc.periodCards(batches, LocalDate.of(2026, 10, 7), counts);
        assertEquals(List.of("today", "week", "month"), cards.stream().map(card -> card.period().key()).toList());

        assertEquals(List.of(1), cards.get(0).batchIds());
        assertEquals(LocalDate.of(2026, 10, 7), cards.get(0).from());
        assertEquals(List.of(1, 2), cards.get(1).batchIds());
        assertEquals(LocalDate.of(2026, 10, 1), cards.get(1).from());
        assertEquals(List.of(1, 2, 3), cards.get(2).batchIds(), "30 days back from the 7th is the 8th");
        assertEquals(3, cards.get(2).count());
        assertEquals(3, cards.get(2).passed());
        assertEquals(5, cards.get(2).failed());
    }

    // ------------------------------------------------------------------ batch aggregates

    @Test
    @DisplayName("A batch's rate sums its counts first, and its run cells drop EasyRec's placeholder")
    void summarise() {
        List<BatchRow> batches = List.of(
                new BatchRow(1, null, null, "ops", 1, null, null, null, 0),
                new BatchRow(2, null, null, "ops", 1, null, null, null, 0));
        List<BatchSummary> summaries = service.summarise(batches);

        BatchSummary first = summaries.get(0);
        assertEquals(110 * 100.0d / 150, first.matchRate(), 1e-9, "(80 + 30) / max(150, 150)");
        assertEquals(1, first.reconciliationsPassed());
        assertEquals(1, first.reconciliationsFailed());
        assertEquals(150L, first.sourceRows());
        assertEquals(150L, first.targetRows());
        assertEquals(1, first.runCount());
        assertEquals("/proj", first.runCells().get("Project"));
        assertEquals("/p/project.xml", first.runCells().get("Template Path"));
        assertEquals("SRC", first.runCells().get("Source Alias"));
        assertEquals("-", first.runCells().get("Source Label"), "<Undefined> is no value");
        assertEquals(DashboardService.RUN_COLUMNS, List.copyOf(first.runCells().keySet()));

        BatchSummary empty = summaries.get(1);
        assertNull(empty.matchRate(), "no statistics: no rate");
        assertEquals(0, empty.reconciliationsPassed());
        assertEquals(0L, empty.sourceRows());
        assertEquals(0, empty.runCount());
        assertTrue(empty.runCells().values().stream().allMatch("-"::equals));
    }

    @Test
    @DisplayName("Pass and fail counts are per reconciliation, from the statistics rows")
    void statusCounts() {
        Map<Integer, int[]> counts = service.reconciliationStatusCounts(List.of(1, 2, 3));
        assertArrayEquals(new int[] {1, 1}, counts.get(1));
        assertArrayEquals(new int[] {0, 0}, counts.get(2));
        assertArrayEquals(new int[] {1, 0}, counts.get(3));
        assertTrue(service.reconciliationStatusCounts(List.of()).isEmpty());
    }

    // -------------------------------------------------------------------- reconciliations

    @Test
    @DisplayName("One reconciliation per context row, with the statistics of its own template")
    void reconciliations() {
        List<Reconciliation> recs = service.findReconciliations(List.of(1), null);
        assertEquals(2, recs.size());
        Reconciliation a = recs.get(0);
        assertEquals(10, a.templateId());
        assertEquals(10, a.statsTemplateId());
        assertEquals("/rec/a.xml", a.templatePath());
        assertEquals(80.0d, a.matchRate());
        assertEquals(StatusLabel.PASSED, a.status());
        assertEquals(50.0d, recs.get(1).matchRate());
        assertEquals(StatusLabel.FAILED, recs.get(1).status());
    }

    @Test
    @DisplayName("Statistics filed one id along are found, and no statistics is no outcome")
    void legacyAndMissingStatistics() {
        List<Reconciliation> recs = service.findReconciliations(List.of(3), null);
        Reconciliation legacy = recs.stream().filter(rec -> rec.templateId() == 20).findFirst().orElseThrow();
        assertEquals(21, legacy.statsTemplateId());
        assertEquals(100.0d, legacy.matchRate());

        Reconciliation silent = recs.stream().filter(rec -> rec.templateId() == 11).findFirst().orElseThrow();
        assertNull(silent.stats());
        assertNull(silent.matchRate());
        assertEquals(StatusLabel.UNKNOWN, silent.status(), "the context's hard-coded 0 is not an outcome");
    }

    @Test
    @DisplayName("A path fragment narrows the reconciliations, case aside")
    void pathFragment() {
        List<Reconciliation> recs = service.findReconciliations(List.of(1, 3), " B.XML ");
        assertEquals(2, recs.size());
        assertTrue(recs.stream().allMatch(rec -> rec.templateId() == 11));
        assertTrue(service.findReconciliations(List.of(1, 3), "nothing like it").isEmpty());
        assertTrue(service.findReconciliations(List.of(), null).isEmpty());
        assertEquals(4, service.findReconciliations(List.of(1, 3), "  ").size(), "a blank fragment is no filter");
    }

    @Test
    @DisplayName("Sorting keeps the rows with no value last, in either direction")
    void comparator() {
        List<Reconciliation> recs = new ArrayList<>(service.findReconciliations(List.of(1, 3), null));
        recs.sort(DashboardService.reconciliationComparator("Match Rate", true));
        assertEquals(List.of(50.0d, 80.0d, 100.0d), rates(recs).subList(0, 3));
        assertNull(rates(recs).get(3));

        recs.sort(DashboardService.reconciliationComparator("Match Rate", false));
        assertEquals(List.of(100.0d, 80.0d, 50.0d), rates(recs).subList(0, 3));
        assertNull(rates(recs).get(3), "no rate stays last when descending");

        recs.sort(DashboardService.reconciliationComparator("Project", false));
        assertEquals(List.of("Proj B", "proj a"), recs.subList(0, 2).stream()
                .map(rec -> rec.context().name()).toList());
        assertTrue(recs.subList(2, 4).stream().allMatch(rec -> "<Undefined>".equals(rec.context().name())));

        recs.sort(DashboardService.reconciliationComparator("Template ID", true));
        assertEquals(List.of(10, 11, 11, 20), recs.stream().map(Reconciliation::templateId).toList());
    }

    // ---------------------------------------------------------------------- filter values

    @Test
    @DisplayName("Filter options are cleaned of the placeholder and sorted case-insensitively")
    void filterOptions() {
        Map<String, List<String>> options = service.filterOptions();
        assertEquals(List.of("Project", "Source", "Target", "User"), List.copyOf(options.keySet()));
        assertEquals(List.of("proj a", "Proj B"), options.get("Project"));
        assertEquals(List.of("Murex"), options.get("Source"));
        assertEquals(List.of(), options.get("Target"));
        assertEquals(List.of("ops", "zed"), options.get("User"));
    }

    @Test
    @DisplayName("Timestamps are read in the service's zone")
    void timestamps() {
        DashboardService utc = new DashboardService(null, ZoneOffset.UTC);
        assertEquals(ZoneOffset.UTC, utc.zone());
        assertEquals(LocalDateTime.of(1970, 1, 1, 0, 0), utc.timestampOf(batchAt(1, "1970-01-01T00:00:00")));
        assertNull(utc.timestampOf(batchAt(1, null)));
    }

    // ---------------------------------------------------------------------------- helpers

    private static BatchRow batchAt(int id, String utcTime) {
        Long millis = utcTime == null ? null
                : LocalDateTime.parse(utcTime).toInstant(ZoneOffset.UTC).toEpochMilli();
        return new BatchRow(id, null, null, null, 1, millis, null, null, 0);
    }

    private static List<Integer> ids(List<BatchRow> batches) {
        return batches.stream().map(BatchRow::batchId).toList();
    }

    private static List<Double> rates(List<Reconciliation> recs) {
        List<Double> rates = new ArrayList<>();
        recs.forEach(rec -> rates.add(rec.matchRate()));
        return rates;
    }
}
