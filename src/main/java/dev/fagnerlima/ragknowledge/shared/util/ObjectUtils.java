package dev.fagnerlima.ragknowledge.shared.util;

/** Null and blank checks that read as one condition at the call site. */
public final class ObjectUtils {

    private ObjectUtils() {
    }

    /** Returns {@code true} when any value is {@code null} or a blank {@link CharSequence}; the array itself must not be null. */
    public static boolean anyNullOrBlank(Object... values) {
        for (Object value : values) {
            if (value == null || value instanceof CharSequence text && text.toString().isBlank()) {
                return true;
            }
        }

        return false;
    }
}
