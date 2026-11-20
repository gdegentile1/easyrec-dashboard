package com.finboxsolutions.easyrec.dashboard.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.finboxsolutions.easyrec.dashboard.model.RowStats;
import com.finboxsolutions.easyrec.dashboard.model.RunContextRow;

/** Which TEMPLATE_ID a reconciliation's statistics are filed under. */
class TemplateIdsTest {

    @Test
    @DisplayName("The context id itself is preferred")
    void exactMatch() {
        assertEquals(1, TemplateIds.resolve(1, Set.of(1, 2), Set.of(1, 2)));
    }

    @Test
    @DisplayName("The legacy +1 pairing is kept for older databases")
    void legacyPairing() {
        assertEquals(2, TemplateIds.resolve(1, Set.of(2), Set.of(1)));
        assertEquals(2, TemplateIds.resolve(1, Set.of(2)));
    }

    @Test
    @DisplayName("No borrowing of another reconciliation's statistics in the same run")
    void noBorrowing() {
        assertEquals(1, TemplateIds.resolve(1, Set.of(2), Set.of(1, 2)));
    }

    @Test
    @DisplayName("No statistics at all leaves the context id, and no context id stays null")
    void fallbacks() {
        assertEquals(5, TemplateIds.resolve(5, Set.of()));
        assertNull(TemplateIds.resolve(null, Set.of(1)));
    }

    @Test
    @DisplayName("Ids are grouped per run, rows without a template skipped")
    void idsByRun() {
        List<RowStats> stats = List.of(stats(1, 10), stats(1, 11), stats(2, 10), stats(2, null));
        assertEquals(Map.of(1, Set.of(10, 11), 2, Set.of(10)), TemplateIds.statsTemplateIdsByRun(stats));

        List<RunContextRow> contexts = List.of(context(1, 10), context(3, null));
        assertEquals(Map.of(1, Set.of(10)), TemplateIds.contextTemplateIdsByRun(contexts));
    }

    private static RowStats stats(int runId, Integer templateId) {
        return new RowStats(runId, templateId, 0, 1, 1, 0, 0, 0, 1, 0, 0, null, null, null);
    }

    private static RunContextRow context(int runId, Integer templateId) {
        return new RunContextRow(runId, 0, templateId, null, null, null, null, null, null, null, null, null,
                null, null, null, null, 0, null, null);
    }
}
