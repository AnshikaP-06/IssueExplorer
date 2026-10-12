package com.example.fixture.reporting;

import java.math.BigDecimal;
import java.util.List;

/** Coordinates the revenue summary and the downloadable customer spreadsheet. */
public final class ReportService {
    private final CsvReportWriter writer;

    /** Selects the formatter used for downloadable reports. */
    public ReportService(CsvReportWriter writer) {
        this.writer = writer;
    }

    /** Returns a comma-separated revenue export for the selected customer rows. */
    public String exportRevenue(List<ReportRow> rows) {
        return writer.write(new RevenueReport(rows));
    }

    /** Returns the revenue total shown beside the export action. */
    public BigDecimal summarizeRevenue(List<ReportRow> rows) {
        return new RevenueReport(rows).totalRevenue();
    }
}
