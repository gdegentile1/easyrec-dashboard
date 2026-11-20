package com.finboxsolutions.easyrec.dashboard.ui.pivot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.finboxsolutions.easyrec.dashboard.model.PivotMetric;
import com.finboxsolutions.easyrec.dashboard.model.PivotRow;
import com.finboxsolutions.easyrec.dashboard.service.PivotTreeBuilder;
import com.finboxsolutions.swing.treetable.model.FilterableExcelTreeTableModel;

/** The adapter from the dashboard's pivot tree onto EasyRec's tree table model. */
class DashboardPivotNodesTest {

    @Test
    @DisplayName("The tree table has the Break column then the metrics, and each branch once")
    void treeTableModel() {
        PivotTreeBuilder.Tree tree = PivotTreeBuilder.build(List.of(
                pivot(2, "EUR", "B1"), pivot(3, "EUR", "B2"), pivot(1, "USD")), null);
        FilterableExcelTreeTableModel model =
                DashboardPivotNodes.toTreeTableModel(tree, PivotMetric.DEFAULT_COLUMNS);

        assertEquals(1 + PivotMetric.DEFAULT_COLUMNS.size(), model.getColumnCount());
        assertEquals(DashboardPivotNodes.TREE_COLUMN, model.getColumnName(0));
        assertEquals("Count", model.getColumnName(1));

        Object root = model.getRoot();
        assertEquals(2, model.getChildCount(root), "EUR and USD, not doubled");
        Object eur = model.getChild(root, 0);
        assertEquals(2, model.getChildCount(eur));
        assertEquals(5.0d, model.getValueAt(eur, 1), "the folded count");
    }

    @Test
    @DisplayName("The breakdown tooltip numbers the levels, names computed ones and escapes HTML")
    void breakdownTooltip() {
        assertEquals("<html><b>Pivot breakdown</b><br>1. CCY<br>2. Pivot level <i>(computed by EasyRec)</i>"
                + "<br>3. A&lt;B&amp;C</html>",
                DashboardPivotNodes.breakdownTooltip("[CCY, ==PIVOT_LEVEL==, A<B&C]"));
        assertEquals("<html><b>Pivot breakdown</b><br>1. CCY</html>", DashboardPivotNodes.breakdownTooltip("CCY"));
        assertNull(DashboardPivotNodes.breakdownTooltip(null));
        assertNull(DashboardPivotNodes.breakdownTooltip(" "));
        assertNull(DashboardPivotNodes.breakdownTooltip("[ , ]"));
    }

    private static PivotRow pivot(double unmatched, String... keys) {
        List<String> all = new ArrayList<>(List.of(keys));
        while (all.size() < PivotRow.MAX_KEYS) {
            all.add("");
        }
        Map<PivotMetric, Double> metrics = new EnumMap<>(PivotMetric.class);
        metrics.put(PivotMetric.UNMATCH_COUNT, unmatched);
        return new PivotRow(1, 1, null, keys.length - 1, all, PivotRow.withTotals(metrics));
    }
}
