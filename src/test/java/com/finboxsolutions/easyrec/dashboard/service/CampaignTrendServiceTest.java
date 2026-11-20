package com.finboxsolutions.easyrec.dashboard.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.finboxsolutions.easyrec.dashboard.TestDatabase;
import com.finboxsolutions.easyrec.dashboard.model.PatternTrend;
import com.finboxsolutions.easyrec.dashboard.service.CampaignTrendService.CampaignBaseline;
import com.finboxsolutions.easyrec.dashboard.service.CampaignTrendService.CauseTrend;
import com.finboxsolutions.easyrec.dashboard.service.CampaignTrendService.SignatureBaseline;

/** A live campaign's causes against the archived runs of its templates. */
class CampaignTrendServiceTest {

    private static final String REC_A = "REPORTING/REC_A";
    private static final String REC_B = "REPORTING/REC_B";
    private static final String REC_C = "REPORTING/REC_C";
    private static final List<String> CAMPAIGN = List.of(REC_A, REC_B, REC_C);

    private TestDatabase database;
    private CampaignTrendService service;

    /**
     * Runs 1 to 3, one per batch. REC_A was reconciled on all three, REC_B on runs 1 and 2,
     * REC_C never archived.
     *
     * <pre>
     *                         run 1  run 2  run 3
     * sigA on REC_A  (10)       4      6      7
     * sigA on REC_B  (20)       2      0      -
     * sigB on REC_A  (11)       -      -      5
     * sigC on REC_A  (12)       3      0      -
     * </pre>
     */
    @BeforeEach
    void setUp() {
        database = TestDatabase.create()
                .template(1, REC_A)
                .template(2, REC_B)
                .template(3, REC_C)
                .template(5, "projects/campaign.xml")
                .template(6, "OTHER/rec_a.xml");
        for (int run = 1; run <= 3; run++) {
            database.batch(run, "2026-10-0" + run + " 08:00:00").run(run, run, 5);
        }
        database.run(4, 3, 5)
                .pattern(10, 1, "sigA", "AMOUNT / Constant factor / 2", 1, null)
                .pattern(20, 2, "sigA", "AMOUNT / Constant factor / 2", 1, null)
                .pattern(11, 1, "sigB", "BOOK / Single substitution / X>Y", 3, null)
                .pattern(12, 1, "sigC", "CCY / Single substitution / EUR>USD", 1, null)
                .patternStat(1, 10, 4).patternStat(2, 10, 6).patternStat(3, 10, 7)
                .patternStat(1, 20, 2).patternStat(2, 20, 0)
                .patternStat(3, 11, 5)
                .patternStat(1, 12, 3).patternStat(2, 12, 0);
        service = new CampaignTrendService(database.dao(), 0.10d);
    }

    @Test
    @DisplayName("The reference is each template's latest run before the live one; a cause sums its templates")
    void baselineBeforeTheLiveRun() {
        CampaignBaseline baseline = service.baselineOf(CAMPAIGN, 3);
        assertTrue(baseline.available());
        assertEquals(Set.of(REC_A, REC_B), baseline.trackedPaths());
        assertEquals(Set.of(REC_C), baseline.untrackedPaths());
        assertEquals(Map.of(REC_A, 2, REC_B, 2), baseline.referenceRuns());
        assertTrue(baseline.isTracked(REC_A));
        assertFalse(baseline.isTracked(REC_C));
        assertFalse(baseline.isTracked(null));

        SignatureBaseline a = baseline.baselineOf("sigA");
        assertEquals(6L, a.previous(), "6 on REC_A plus 0 on REC_B, both on run 2");
        assertTrue(a.seenBefore());
        assertEquals(1, a.firstSeenRun());
        assertEquals(Set.of(REC_A), a.templatePaths(), "only the templates that counted it");
        assertEquals("AMOUNT / Constant factor / 2", a.description());

        assertNull(baseline.baselineOf("sigB"), "first counted after the reference run");
        SignatureBaseline c = baseline.baselineOf("sigC");
        assertEquals(0L, c.previous());
        assertTrue(c.seenBefore());
        assertNull(baseline.baselineOf(null));
    }

    @Test
    @DisplayName("Without a live run id, the latest archived run of each template is the reference")
    void baselineOfTheLatestRuns() {
        CampaignBaseline baseline = service.baselineOf(CAMPAIGN, null);
        assertEquals(Map.of(REC_A, 3, REC_B, 2), baseline.referenceRuns());
        assertEquals(7L, baseline.baselineOf("sigA").previous());
        assertEquals(5L, baseline.baselineOf("sigB").previous());
        assertEquals(3, baseline.baselineOf("sigB").firstSeenRun());
    }

    @Test
    @DisplayName("No path, or no pattern tables, is no baseline rather than a failure")
    void noBaseline() {
        assertSame(CampaignBaseline.NONE, service.baselineOf(List.of(), 1));
        assertSame(CampaignBaseline.NONE, service.baselineOf(Arrays.asList(" ", null), 1));
        assertSame(CampaignBaseline.NONE, service.baselineOf(null, 1));
        assertFalse(CampaignBaseline.NONE.available());

        CampaignBaseline unknown = service.baselineOf(List.of("NOWHERE/REC_Z"), null);
        assertFalse(unknown.available());
        assertEquals(Set.of("NOWHERE/REC_Z"), unknown.untrackedPaths());

        TestDatabase legacy = TestDatabase.create(false).template(1, REC_A);
        CampaignBaseline old = new CampaignTrendService(legacy.dao(), 0.10d).baselineOf(List.of(REC_A), null);
        assertFalse(old.available());
        assertEquals(Set.of(REC_A), old.untrackedPaths());
    }

    @Test
    @DisplayName("A live cause is judged on its tracked templates with the pattern screen's rules")
    void trendOf() {
        CampaignBaseline baseline = service.baselineOf(CAMPAIGN, 3);

        CauseTrend rising = service.trendOf(baseline, "sigA", Map.of(REC_A, 7L, REC_B, 0L));
        assertEquals(PatternTrend.INCREASING, rising.trend(), "7 against 6 is more than 10% up");
        assertEquals(7L, rising.comparedOccurrences());
        assertEquals(6L, rising.previous());
        assertEquals(1L, rising.change());
        assertEquals(1, rising.firstSeenRun());
        assertFalse(rising.partial());

        Map<String, Long> withUntracked = new LinkedHashMap<>();
        withUntracked.put(REC_A, 6L);
        withUntracked.put(REC_C, 30L);
        CauseTrend partial = service.trendOf(baseline, "sigA", withUntracked);
        assertEquals(PatternTrend.STABLE, partial.trend(), "REC_C's 30 breaks are left out");
        assertEquals(6L, partial.comparedOccurrences());
        assertEquals(1, partial.untrackedTemplates());
        assertTrue(partial.partial());

        CauseTrend fresh = service.trendOf(baseline, "sigNew", Map.of(REC_A, 2L));
        assertEquals(PatternTrend.NEW, fresh.trend());
        assertNull(fresh.previous());
        assertNull(fresh.change());

        assertEquals(PatternTrend.REAPPEARED, service.trendOf(baseline, "sigC", Map.of(REC_A, 2L)).trend());
        assertEquals(PatternTrend.NOT_DETECTED, service.trendOf(baseline, "sigA", Map.of()).trend());
        assertEquals(PatternTrend.STILL_NOT_DETECTED, service.trendOf(baseline, "sigC", null).trend());
    }

    @Test
    @DisplayName("A cause found only on untracked templates gets no trend, and no baseline gives none")
    void noComparison() {
        CampaignBaseline baseline = service.baselineOf(CAMPAIGN, 3);
        CauseTrend untracked = service.trendOf(baseline, "sigA", Map.of(REC_C, 4L));
        assertNull(untracked.trend());
        assertEquals(1, untracked.untrackedTemplates());
        assertFalse(untracked.partial(), "nothing was compared at all");

        assertNull(service.trendOf(CampaignBaseline.NONE, "sigA", Map.of(REC_A, 1L)));
        assertNull(service.trendOf(null, "sigA", Map.of(REC_A, 1L)));
    }

    @Test
    @DisplayName("Vanished causes: counted on the reference runs, absent live, most breaks first")
    void vanished() {
        database.pattern(13, 1, "sigD", "DESK / Single substitution / A>B", 1, null)
                .patternStat(2, 13, 9);
        CampaignBaseline baseline = service.baselineOf(CAMPAIGN, 3);
        assertEquals(List.of("sigD", "sigA"), signatures(service.vanished(baseline, Set.of())));
        assertEquals(List.of("sigD", "sigA"), signatures(service.vanished(baseline, null)));
        assertEquals(List.of("sigD"), signatures(service.vanished(baseline, Set.of("sigA"))),
                "sigC was at zero: nothing vanished");
        assertTrue(service.vanished(CampaignBaseline.NONE, Set.of()).isEmpty());
    }

    @Test
    @DisplayName("A linked pattern continues the earlier one's history, which is no longer reported alone")
    void linkedPatterns() {
        // sigE replaces sigC on REC_A from run 3.
        database.pattern(14, 1, "sigE", "CCY / Value mapping table / EUR>USD", 3, 12)
                .patternStat(3, 14, 2);
        CampaignBaseline baseline = service.baselineOf(CAMPAIGN, null);
        SignatureBaseline e = baseline.baselineOf("sigE");
        assertEquals(2L, e.previous());
        assertTrue(e.seenBefore());
        assertEquals(1, e.firstSeenRun(), "inherited from sigC's run 1");
        assertNull(baseline.baselineOf("sigC"), "superseded by sigE");
    }

    @Test
    @DisplayName("The first run of a batch is its smallest run id")
    void firstRunOfBatch() {
        assertEquals(3, service.firstRunOfBatch(3));
        assertEquals(1, service.firstRunOfBatch(1));
        assertNull(service.firstRunOfBatch(99));
        assertNull(service.firstRunOfBatch(null));
    }

    @Test
    @DisplayName("A template name resolves to the one path ending with it, case and extension aside")
    void pathsByTemplateName() {
        Map<String, String> resolved = service.pathsByTemplateName(
                Arrays.asList("rec_b", "REC_C ", "campaign", "REC_A", "missing", " ", null));
        assertEquals(REC_B, resolved.get("rec_b"));
        assertEquals(REC_C, resolved.get("REC_C "));
        assertEquals("projects/campaign.xml", resolved.get("campaign"));
        assertFalse(resolved.containsKey("REC_A"), "REPORTING/REC_A and OTHER/rec_a.xml: ambiguous");
        assertFalse(resolved.containsKey("missing"));
        assertEquals(3, resolved.size());

        assertTrue(service.pathsByTemplateName(List.of()).isEmpty());
        assertTrue(service.pathsByTemplateName(null).isEmpty());
        assertTrue(new CampaignTrendService(TestDatabase.empty().dao(), 0.1d)
                .pathsByTemplateName(List.of("REC_A")).isEmpty(), "no dashboard schema");
    }

    private static List<String> signatures(List<SignatureBaseline> baselines) {
        return baselines.stream().map(SignatureBaseline::signature).toList();
    }
}
