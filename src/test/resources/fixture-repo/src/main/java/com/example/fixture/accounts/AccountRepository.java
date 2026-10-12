package com.example.fixture.accounts;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Finds fixture accounts by a case-insensitive username. No credentials are stored. */
public final class AccountRepository {
    private final Map<String, Account> accounts = new HashMap<>();

    /** Adds or replaces an account in the directory. */
    public void save(Account account) {
        accounts.put(account.getUsername().toLowerCase(Locale.ROOT), account);
    }

    /** Returns the account associated with a username, if present. */
    public Optional<Account> findByUsername(String username) {
        return Optional.ofNullable(accounts.get(username.toLowerCase(Locale.ROOT)));
    }
}
