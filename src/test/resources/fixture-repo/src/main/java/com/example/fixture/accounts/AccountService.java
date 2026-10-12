package com.example.fixture.accounts;

/** Applies account lockout behavior to the result supplied by a credential verifier. */
public final class AccountService {
    private final AccountRepository repository;

    /** Uses the account directory without depending on an authentication framework. */
    public AccountService(AccountRepository repository) {
        this.repository = repository;
    }

    /** Records a verifier result and refuses sign-in when the account is locked. */
    public boolean authenticate(String username, boolean credentialsAccepted) {
        var account = repository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("Unknown account"));
        if (account.isLocked()) {
            throw new IllegalStateException("Account is locked");
        }
        if (!credentialsAccepted) {
            account.recordFailure();
            return false;
        }
        account.resetFailures();
        return true;
    }
}
