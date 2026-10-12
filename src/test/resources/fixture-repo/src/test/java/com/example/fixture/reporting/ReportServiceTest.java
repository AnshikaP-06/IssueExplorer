package com.example.fixture.reporting;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises decimal totals, immutable report rows, and spreadsheet cell quoting. */
class ReportServiceTest {
    /** Revenue totals preserve exact decimal amounts, including refunds. */
    @Test
    void totalsRevenueWithoutRoundingDrift() {
        var rows = List.of(new ReportRow("First", new BigDecimal("0.10")),
                new ReportRow("Second", new BigDecimal("0.20")));
        var service = new ReportService(new CsvReportWriter());
        assertEquals(new BigDecimal("0.30"), service.summarizeRevenue(rows));
        assertEquals(BigDecimal.ZERO, service.summarizeRevenue(List.of()));
    }

    /** Labels containing commas, quotes, and newlines remain single CSV cells. */
    @Test
    void quotesSpecialCustomerLabels() {
        var writer = new CsvReportWriter();
        var service = new ReportService(writer);
        assertEquals("customer,amount\n\"North, West\",12.50", service.exportRevenue(
                List.of(new ReportRow("North, West", new BigDecimal("12.50")))));
        assertEquals("\"A\"\"B\"", writer.escapeCell("A\"B"));
        assertEquals("\"A\nB\"", writer.escapeCell("A\nB"));
        assertEquals("plain", writer.escapeCell("plain"));
    }

    /** A report owns a copy of its row list. */
    @Test
    void reportRowsCannotBeModifiedAfterCreation() {
        var rows = new ArrayList<ReportRow>();
        rows.add(new ReportRow("First", BigDecimal.ONE));
        var report = new RevenueReport(rows);
        rows.clear();
        assertEquals(1, report.getRows().size());
        assertThrows(UnsupportedOperationException.class, () -> report.getRows().clear());
    }
}
