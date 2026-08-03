package com.clougence.test.framework.assertion;

import java.util.Objects;

public final class TestAssertions {

    private TestAssertions() {
    }

    public static void isTrue(String message, boolean value) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    public static void isFalse(String message, boolean value) {
        isTrue(message, !value);
    }

    public static void notNull(String message, Object value) {
        isTrue(message, value != null);
    }

    public static void equals(String message, Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
