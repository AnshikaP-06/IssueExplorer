package com.example.fixture.accounts;

/** Tracks a user's sign-in failures and the account lockout state. */
public final class Account {
    private final String username;
    private int failedAttempts;

    /** Creates an account with no failed sign-in attempts. */
    public Account(String username) {
        this.username = username;
    }

    /** Returns the name used by the account directory. */
    public String getUsername() {
        return username;
    }

    /** Records a rejected sign-in attempt. */
    public void recordFailure() {
        failedAttempts++;
    }

    /** Clears failed attempts after an accepted sign-in. */
    public void resetFailures() {
        failedAttempts = 0;
    }

    /** Locks an account after five consecutive rejected attempts. */
    public boolean isLocked() {
        return failedAttempts >= 5;
    }
}
