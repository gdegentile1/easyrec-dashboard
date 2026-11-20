package com.finboxsolutions.easyrec.dashboard.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.finboxsolutions.easyrec.dashboard.TestDatabase;

/** The JDBC helpers: IN chunks, boxed nulls, tolerant STATUS and temporal reads. */
class SqlTest {

    private static final String ROW = "SELECT CAST(NULL AS INT) AS NOTHING, 5 AS FIVE, CAST(0 AS INT) AS ZERO, "
            + "'  7 ' AS PADDED, 'abc' AS WORD, '' AS BLANK, 2.5 AS DEC, "
            + "TIMESTAMP '2026-10-01 08:30:00' AS STAMP, DATE '2026-10-01' AS DAY_ONLY, "
            + "'1759305600000' AS EPOCH_TEXT, CAST(1759305600000 AS BIGINT) AS EPOCH_NUMBER, "
            + "CAST(NULL AS TIMESTAMP) AS NO_STAMP";

    @Test
    @DisplayName("Ids are split into chunks of 900, in order, and no ids give no chunk")
    void chunks() {
        assertTrue(Sql.chunks(List.of()).isEmpty());
        List<Integer> ids = IntStream.rangeClosed(1, 1801).boxed().toList();
        List<List<Integer>> chunks = Sql.chunks(ids);
        assertEquals(List.of(900, 900, 1), chunks.stream().map(List::size).toList());
        List<Integer> joined = new ArrayList<>();
        chunks.forEach(joined::addAll);
        assertEquals(ids, joined);
    }

    @Test
    @DisplayName("Placeholders are a parenthesised list of binds")
    void placeholders() {
        assertEquals("()", Sql.placeholders(0));
        assertEquals("(?)", Sql.placeholders(1));
        assertEquals("(?, ?, ?)", Sql.placeholders(3));
    }

    @Test
    @DisplayName("A null column stays null, not getInt's zero; a count reads null as zero")
    void boxedNulls() throws SQLException {
        query(rows -> {
            assertNull(Sql.integer(rows, "NOTHING"));
            assertEquals(0, Sql.integer(rows, "ZERO"));
            assertEquals(5, Sql.integer(rows, "FIVE"));
            assertNull(Sql.bigint(rows, "NOTHING"));
            assertEquals(5L, Sql.bigint(rows, "FIVE"));
            assertNull(Sql.decimal(rows, "NOTHING"));
            assertEquals(2.5d, Sql.decimal(rows, "DEC"));
            assertEquals(0L, Sql.count(rows, "NOTHING"));
        });
    }

    @Test
    @DisplayName("STATUS is read as text: padded numbers parse, words and blanks are absent")
    void status() throws SQLException {
        query(rows -> {
            assertEquals(5, Sql.status(rows, "FIVE"));
            assertEquals(7, Sql.status(rows, "PADDED"));
            assertNull(Sql.status(rows, "WORD"));
            assertNull(Sql.status(rows, "BLANK"));
            assertNull(Sql.status(rows, "NOTHING"));
        });
    }

    @Test
    @DisplayName("SYS_TIME and SYS_DATE are read by type, an epoch written as text or number is kept")
    void epochMillis() throws SQLException {
        long stamp = LocalDateTime.of(2026, 10, 1, 8, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long day = LocalDate.of(2026, 10, 1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        query(rows -> {
            assertEquals(stamp, Sql.epochMillis(rows, "STAMP"));
            assertEquals(day, Sql.epochMillis(rows, "DAY_ONLY"));
            assertEquals(1_759_305_600_000L, Sql.epochMillis(rows, "EPOCH_TEXT"));
            assertEquals(1_759_305_600_000L, Sql.epochMillis(rows, "EPOCH_NUMBER"));
            assertEquals(day, Sql.epochMillis(rows, "NO_STAMP", "DAY_ONLY"), "the first column holding an instant");
            assertNull(Sql.epochMillis(rows, "NO_STAMP"));
            assertNull(Sql.epochMillis(rows, "WORD"), "neither a date nor a number");
            assertNull(Sql.epochMillis(rows));
        });
    }

    @FunctionalInterface
    private interface RowCheck {
        void check(ResultSet rows) throws SQLException;
    }

    private static void query(RowCheck check) throws SQLException {
        TestDatabase database = TestDatabase.empty();
        try (Connection connection = database.dataSource().getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(ROW)) {
            assertTrue(rows.next());
            check.check(rows);
        }
    }
}
