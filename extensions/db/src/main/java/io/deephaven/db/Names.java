package io.deephaven.db;

final class Names {
    private static final String IDENTIFIER = "[A-Za-z_][A-Za-z0-9_]*";

    private Names() {}

    static void checkIdentifier(final String kind, final String value) {
        if (value == null || !value.matches(IDENTIFIER)) {
            throw new IllegalArgumentException(kind + " must match " + IDENTIFIER + ": " + value);
        }
    }

    static void checkDate(final String date) {
        if (date == null || date.isEmpty() || date.indexOf('/') >= 0 || date.indexOf('\\') >= 0
                || date.indexOf('=') >= 0) {
            throw new IllegalArgumentException("Invalid date partition: " + date);
        }
    }
}
