package com.finboxsolutions.easyrec.dashboard.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Reading the column and the type out of a pattern description, and the RCA rule name. */
class PatternKindTest {

    @Test
    @DisplayName("Column and type are read around a known type label")
    void columnAndType() {
        PatternKind shift = PatternKind.of("VALUE_DATE / Constant date shift / 1");
        assertEquals("VALUE_DATE", shift.column());
        assertEquals("DATE_SHIFT", shift.typeName());
        assertEquals("Constant date shift", shift.typeLabel());
        assertTrue(shift.isKnown());
        assertTrue(shift.hasRcaRule());
    }

    @Test
    @DisplayName("The rule name is Pattern_<column>_<type>_<first 8 of the signature>, column sanitised")
    void expectedRuleName() {
        assertEquals("Pattern_VALUE_DATE_DATE_SHIFT_98f55b15",
                PatternKind.of("VALUE_DATE / Constant date shift / 1").expectedRuleName("98f55b15deadbeef"));
        assertEquals("Pattern_M_F_CTP_SINGLE_SUBSTITUTION_e9d5f976",
                PatternKind.of("M_F_CTP / Single substitution / LEHMAN>DEFAULT_LEHMAN")
                        .expectedRuleName("e9d5f976aaaaaaaa"));
        assertEquals("Pattern_M_F_CTP_SINGLE_SUBSTITUTION_5a7b5b8e",
                PatternKind.of("M F-CTP / Single substitution / IT>INT").expectedRuleName("5a7b5b8e00"));
        assertNull(PatternKind.of("M_F_CTP / Single substitution / A>B").expectedRuleName("1234567"),
                "a signature shorter than 8 characters");
        assertNull(PatternKind.of("M_F_CTP / Single substitution / A>B").expectedRuleName(null));
    }

    @Test
    @DisplayName("The earliest type label wins when the key holds the separator and another label")
    void keyHoldingALabel() {
        assertEquals("SINGLE_SUBSTITUTION",
                PatternKind.of("M_F_CTP / Single substitution / A / Constant factor>B").typeName());
        assertEquals("M_F_CTP", PatternKind.of("M_F_CTP / Single substitution / A / Constant factor / 2").column());
    }

    @Test
    @DisplayName("A label must end the description or be followed by the separator")
    void labelBoundary() {
        assertFalse(PatternKind.of("X / Constant factorial / 2").isKnown());
        assertTrue(PatternKind.of("X / Constant factor").isKnown(), "a label ending the description");
    }

    @Test
    @DisplayName("Unexplained, row-level and unknown patterns get no rule")
    void noRule() {
        PatternRow unexplained = new PatternRow(1, 1, "0123456789", "AMOUNT / No rule found", 1, null);
        assertTrue(PatternKind.isUnexplained(unexplained));
        assertNull(PatternKind.of(unexplained).expectedRuleName("0123456789"));
        assertFalse(PatternKind.isUnexplained(new PatternRow(1, 1, "s", "VALUE_DATE / Constant date shift / 1", 1, null)));

        PatternKind rowLevel = PatternKind.of("(row) / Rows missing on one side");
        assertNull(rowLevel.column());
        assertEquals("MISSING_ROWS", rowLevel.typeName());
        assertFalse(rowLevel.hasRcaRule());
        assertNull(rowLevel.expectedRuleName("0123456789"));

        assertFalse(PatternKind.of("AMOUNT / Declared type differs / x").hasRcaRule(), "a schema finding");
        assertFalse(PatternKind.of("X / Something new / 1").isKnown());
    }

    @Test
    @DisplayName("A missing description or pattern is UNKNOWN, never a guess")
    void unknown() {
        assertSame(PatternKind.UNKNOWN, PatternKind.of((String) null));
        assertSame(PatternKind.UNKNOWN, PatternKind.of(""));
        assertSame(PatternKind.UNKNOWN, PatternKind.of((PatternRow) null));
        assertSame(PatternKind.UNKNOWN, PatternKind.of("no separator at all"));
        assertFalse(PatternKind.UNKNOWN.hasRcaRule());
        assertFalse(PatternKind.isUnexplained(null));
    }
}
