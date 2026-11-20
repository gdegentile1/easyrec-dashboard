package com.finboxsolutions.easyrec.dashboard.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.finboxsolutions.easyrec.dashboard.model.PivotMetric;
import com.finboxsolutions.easyrec.dashboard.model.PivotRow;
import com.finboxsolutions.easyrec.dashboard.service.PivotTreeBuilder.Level;
import com.finboxsolutions.easyrec.dashboard.service.PivotTreeBuilder.Node;
import com.finboxsolutions.easyrec.dashboard.service.PivotTreeBuilder.Tree;

/** Folding the flat ER_DASHBOARD_PIVOT rows into the breakdown tree. */
class PivotTreeBuilderTest {

    @Test
    @DisplayName("Rows fold into sorted branches; a branch sums its counts and recomputes its ratio")
    void build() {
        List<PivotRow> pivots = List.of(
                pivot(null, 1, metrics(PivotMetric.UNMATCH_COUNT, 3, PivotMetric.UNMATCH_IMPACT, -30,
                        PivotMetric.UNMATCH_SUM_S, 200, PivotMetric.UNMATCH_IMPACT_PCT, -0.15), "EUR", "BOOK2"),
                pivot(null, 0, metrics(PivotMetric.UNMATCH_COUNT, 1, PivotMetric.MISSING_SRC_COUNT, 4), "USD"),
                pivot(null, 1, metrics(PivotMetric.UNMATCH_COUNT, 2, PivotMetric.UNMATCH_IMPACT, 10,
                        PivotMetric.UNMATCH_SUM_S, 100, PivotMetric.UNMATCH_IMPACT_PCT, 0.1), "EUR", "BOOK1"),
                pivot("GBP/BOOK9", null, metrics(PivotMetric.UNMATCH_COUNT, 7)),
                pivot(null, null, metrics(PivotMetric.UNMATCH_COUNT, 99), "<Undefined>"));
        Tree tree = PivotTreeBuilder.build(pivots, "[CCY, ==PIVOT_LEVEL==]");

        assertEquals(List.of("EUR", "BOOK1", "BOOK2", "GBP", "BOOK9", "USD"),
                tree.rows().stream().map(Node::key).toList(), "pre-order, keys sorted, the blank row dropped");
        assertEquals(List.of("EUR", "GBP", "USD"), tree.roots().stream().map(Node::key).toList());
        assertEquals(1, tree.maxDepth());
        assertFalse(tree.isEmpty());

        Node eur = tree.rows().get(0);
        assertEquals(1, eur.id());
        assertEquals(0, eur.parentId());
        assertTrue(eur.aggregated());
        assertTrue(eur.hasChildren());
        assertEquals(2, eur.leafCount());
        assertEquals("CCY", eur.levelName());
        assertEquals(5.0d, eur.value(PivotMetric.UNMATCH_COUNT));
        assertEquals(5.0d, eur.value(PivotMetric.TOTAL_BREAKS));
        assertEquals(-20.0d, eur.value(PivotMetric.UNMATCH_IMPACT));
        assertEquals(-20.0d / 300.0d, eur.value(PivotMetric.UNMATCH_IMPACT_PCT), 1e-12,
                "recomputed from the sums, not -0.15 + 0.1");

        Node book1 = tree.rows().get(1);
        assertEquals(1, book1.parentId());
        assertEquals("EUR/BOOK1", book1.path());
        assertEquals(1, book1.depth());
        assertFalse(book1.aggregated());
        assertFalse(book1.hasChildren());
        assertEquals("Level 2", book1.levelName(), "a computed level has no field name");
        assertEquals(0.1d, book1.value(PivotMetric.UNMATCH_IMPACT_PCT), "a leaf keeps its stored ratio");

        Node usd = tree.rows().get(5);
        assertEquals(5.0d, usd.value(PivotMetric.TOTAL_BREAKS), "1 unmatched + 4 missing");
        assertEquals("GBP/BOOK9", tree.rows().get(4).path(), "split from PIVOT_KEYS as a fallback");

        assertEquals(List.of(new Level(0, "CCY", "CCY"), new Level(1, "", "Level 2")), tree.levels());
    }

    @Test
    @DisplayName("Two rows on one path: the first one wins")
    void duplicatePath() {
        Tree tree = PivotTreeBuilder.build(List.of(
                pivot(null, 0, metrics(PivotMetric.UNMATCH_COUNT, 1), "EUR"),
                pivot(null, 0, metrics(PivotMetric.UNMATCH_COUNT, 9), "EUR")), null);
        assertEquals(1, tree.rows().size());
        assertEquals(1.0d, tree.rows().get(0).value(PivotMetric.UNMATCH_COUNT));
        assertEquals("Level 1", tree.rows().get(0).levelName());
    }

    @Test
    @DisplayName("No row is an empty tree with no level")
    void empty() {
        Tree tree = PivotTreeBuilder.build(List.of(), "[CCY]");
        assertTrue(tree.isEmpty());
        assertTrue(tree.levels().isEmpty());
        assertEquals(0, tree.maxDepth());
    }

    @Test
    @DisplayName("A path stops at the first blank key and at PIVOT_LEVEL; keys are trimmed")
    void pathOf() {
        assertEquals(List.of("A"), PivotTreeBuilder.pathOf(pivot(null, null, Map.of(), " A ", "", "C")));
        assertEquals(List.of("A"), PivotTreeBuilder.pathOf(pivot(null, 0, Map.of(), "A", "B", "C")));
        assertEquals(List.of("A", "B", "C"), PivotTreeBuilder.pathOf(pivot(null, 7, Map.of(), "A", "B", "C")));
        assertEquals(List.of("A", "B"), PivotTreeBuilder.pathOf(pivot("A//B", null, Map.of())));
        assertEquals(List.of(), PivotTreeBuilder.pathOf(pivot(" ", null, Map.of())));
    }

    @Test
    @DisplayName("Breakdown names: brackets stripped, blanks skipped, computed markers unnamed")
    void breakdownNames() {
        assertEquals(List.of(), PivotTreeBuilder.breakdownNames(null));
        assertEquals(List.of(), PivotTreeBuilder.breakdownNames("  "));
        assertEquals(List.of("A", ""), PivotTreeBuilder.breakdownNames("[A, , ==X==]"));
        assertEquals(List.of("A", "B"), PivotTreeBuilder.breakdownNames("A,B"));
    }

    @Test
    @DisplayName("One level per depth, named after its field or numbered")
    void levelOptions() {
        assertEquals(List.of("A", "Level 2", "Level 3"),
                PivotTreeBuilder.levelOptions(List.of("A"), 2).stream().map(Level::name).toList());
    }

    // ---------------------------------------------------------------------------- helpers

    private static PivotRow pivot(String pivotKeys, Integer level, Map<PivotMetric, Double> metrics, String... keys) {
        List<String> all = new ArrayList<>(Arrays.asList(keys));
        while (all.size() < PivotRow.MAX_KEYS) {
            all.add("");
        }
        return new PivotRow(1, 1, pivotKeys, level, all, PivotRow.withTotals(metrics));
    }

    private static Map<PivotMetric, Double> metrics(Object... pairs) {
        Map<PivotMetric, Double> metrics = new EnumMap<>(PivotMetric.class);
        for (int index = 0; index < pairs.length; index += 2) {
            metrics.put((PivotMetric) pairs[index], ((Number) pairs[index + 1]).doubleValue());
        }
        return metrics;
    }
}
