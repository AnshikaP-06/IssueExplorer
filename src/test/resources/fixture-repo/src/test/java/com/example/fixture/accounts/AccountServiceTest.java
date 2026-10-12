package com.example.fixture.accounts;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises account lookup, failed-attempt reset, lockout, and registration policy. */
class AccountServiceTest {
    /** A successful sign-in resets the failure counter before the five-attempt threshold. */
    @Test
    void successfulSignInResetsFailures() {
        var repository = new AccountRepository();
        var account = new Account("Taylor");
        repository.save(account);
        var service = new AccountService(repository);
        for (int i = 0; i < 4; i++) {
            assertFalse(service.authenticate("taylor", false));
        }
        assertTrue(service.authenticate("TAYLOR", true));
        assertFalse(service.authenticate("Taylor", false));
        assertFalse(account.isLocked());
    }

    /** Five consecutive failures lock the account; nonexistent users produce a clear error. */
    @Test
    void locksAfterFiveFailures() {
        var repository = new AccountRepository();
        var account = new Account("Morgan");
        repository.save(account);
        var service = new AccountService(repository);
        for (int i = 0; i < 5; i++) {
            assertFalse(service.authenticate("Morgan", false));
        }
        assertTrue(account.isLocked());
        assertThrows(IllegalStateException.class, () -> service.authenticate("Morgan", true));
        assertThrows(IllegalArgumentException.class, () -> service.authenticate("missing", true));
    }

    /** Checks only password shape using generated test input, never stored credentials. */
    @Test
    void validatesPasswordShape() {
        var policy = new PasswordPolicy();
        String candidate = "A" + "x".repeat(10) + (char) ('0' + 7);
        assertTrue(policy.isAcceptable(candidate));
        assertFalse(policy.isAcceptable(null));
        assertFalse(policy.isAcceptable("short"));
        assertFalse(policy.isAcceptable("a".repeat(12)));
    }
}
