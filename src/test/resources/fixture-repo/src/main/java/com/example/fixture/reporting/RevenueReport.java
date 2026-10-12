package com.example.fixture.reporting;

import java.math.BigDecimal;
import java.util.List;

/** An immutable snapshot of the rows included in a revenue report. */
public final class RevenueReport {
    private final List<ReportRow> rows;

    /** Copies the selected rows so callers cannot change the report after creation. */
    public RevenueReport(List<ReportRow> rows) {
        this.rows = List.copyOf(rows);
    }

    /** Returns the report rows in their display order. */
    public List<ReportRow> getRows() {
        return rows;
    }

    /** Totals the included amounts using decimal arithmetic. */
    public BigDecimal totalRevenue() {
        return rows.stream().map(ReportRow::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
