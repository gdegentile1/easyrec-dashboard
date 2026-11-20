package com.finboxsolutions.easyrec.dashboard.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The one match rate definition, its colour bands and the impact ratio. */
class RatesTest {

    @Test
    @DisplayName("The match rate divides by the larger side, and is absent with nothing compared")
    void matchRate() {
        assertEquals(90.0d, Rates.matchRate(90, 100, 80));
        assertEquals(90.0d, Rates.matchRate(90, 80, 100));
        assertNull(Rates.matchRate(0, 0, 0));
    }

    @Test
    @DisplayName("A percentage becomes the fraction the percent renderers expect")
    void fraction() {
        assertEquals(0.955d, Rates.fraction(95.5d), 1e-12);
        assertNull(Rates.fraction(null));
    }

    @Test
    @DisplayName("95 and above is good, 80 and above a warning, below that bad")
    void tone() {
        assertEquals(Rates.Tone.GOOD, Rates.toneOf(100.0d));
        assertEquals(Rates.Tone.GOOD, Rates.toneOf(Rates.GOOD_THRESHOLD));
        assertEquals(Rates.Tone.WARN, Rates.toneOf(94.99d));
        assertEquals(Rates.Tone.WARN, Rates.toneOf(Rates.WARN_THRESHOLD));
        assertEquals(Rates.Tone.BAD, Rates.toneOf(79.99d));
        assertEquals(Rates.Tone.NONE, Rates.toneOf(null));
    }

    @Test
    @DisplayName("The impact ratio divides by the absolute base, and falls back to the sign")
    void impactRatio() {
        assertEquals(0.5d, Rates.impactRatio(5.0d, -10.0d));
        assertEquals(-0.5d, Rates.impactRatio(-5.0d, 10.0d));
        assertEquals(0.0d, Rates.impactRatio(0.0d, 0.0d));
        assertEquals(1.0d, Rates.impactRatio(3.0d, 0.0d));
        assertEquals(-1.0d, Rates.impactRatio(-3.0d, 0.0d));
    }
}
