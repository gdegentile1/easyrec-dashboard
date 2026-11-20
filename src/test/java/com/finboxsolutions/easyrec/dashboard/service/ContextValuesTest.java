package com.finboxsolutions.easyrec.dashboard.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** EasyRec's {@code <Undefined>} placeholder and blanks mean "no value". */
class ContextValuesTest {

    @Test
    @DisplayName("Blanks and the placeholder are cleaned away, anything else trimmed")
    void clean() {
        assertNull(ContextValues.clean(null));
        assertNull(ContextValues.clean(""));
        assertNull(ContextValues.clean("   "));
        assertNull(ContextValues.clean("<Undefined>"));
        assertNull(ContextValues.clean(" <Undefined> "));
        assertEquals("FX", ContextValues.clean(" FX "));
        assertEquals("<undefined>", ContextValues.clean("<undefined>"), "the placeholder is matched exactly");
    }

    @Test
    @DisplayName("A cell shows a dash for no value")
    void display() {
        assertEquals("-", ContextValues.display(null));
        assertEquals("-", ContextValues.display("<Undefined>"));
        assertEquals("FX", ContextValues.display("FX "));
    }
}
