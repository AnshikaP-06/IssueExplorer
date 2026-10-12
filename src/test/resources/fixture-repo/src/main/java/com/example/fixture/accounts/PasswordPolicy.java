package com.example.fixture.accounts;

/** Checks password shape during registration; it does not store or verify credentials. */
public final class PasswordPolicy {
    /** Requires at least twelve characters, including an uppercase letter and a digit. */
    public boolean isAcceptable(String candidate) {
        return candidate != null && candidate.length() >= 12
                && candidate.chars().anyMatch(Character::isUpperCase)
                && candidate.chars().anyMatch(Character::isDigit);
    }
}
