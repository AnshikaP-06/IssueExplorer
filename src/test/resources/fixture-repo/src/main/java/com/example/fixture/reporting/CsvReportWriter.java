package com.example.fixture.reporting;

import java.util.stream.Collectors;

/** Formats revenue rows for spreadsheets, preserving labels that contain separators. */
public final class CsvReportWriter {
    /** Writes a header and one comma-separated line per customer amount. */
    public String write(RevenueReport report) {
        return "customer,amount\n" + report.getRows().stream()
                .map(row -> escapeCell(row.getCustomer()) + "," + row.getAmount().toPlainString())
                .collect(Collectors.joining("\n"));
    }

    /** Quotes cells containing commas, quotes, or line breaks and doubles embedded quotes. */
    public String escapeCell(String value) {
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
