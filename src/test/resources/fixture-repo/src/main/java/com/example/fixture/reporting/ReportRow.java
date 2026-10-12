package com.example.fixture.reporting;

import java.math.BigDecimal;

/** One customer label and monetary amount in a revenue export. */
public final class ReportRow {
    private final String customer;
    private final BigDecimal amount;

    /** Creates a row without converting money through binary floating point. */
    public ReportRow(String customer, BigDecimal amount) {
        this.customer = customer;
        this.amount = amount;
    }

    /** Returns the customer label displayed in the export. */
    public String getCustomer() {
        return customer;
    }

    /** Returns the exact monetary amount for this row. */
    public BigDecimal getAmount() {
        return amount;
    }
}
