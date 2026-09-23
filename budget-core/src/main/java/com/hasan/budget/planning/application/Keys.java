package com.hasan.budget.planning.application;

import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * The lowercase keys a client sends back to name a choice: {@code "dining-out"}, {@code "locked"}.
 *
 * <p>Derived from the constant rather than listed, so adding a category or a tier needs no second
 * table here. A key is a machine identifier in the same sense a city's slug is: it always travels
 * beside a label written for a person, and it is the label that gets rendered. Keeping keys lowercase
 * and hyphenated is what keeps them visibly distinct from the upper-case constants that must never
 * reach a screen.
 */
public final class Keys {

    private Keys() {}

    public static String of(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /**
     * @throws UnknownKeyException naming every valid key, so the message is enough to fix the request
     */
    public static <E extends Enum<E>> E parse(Class<E> type, String key, String whatItNames) {
        Objects.requireNonNull(type, "type");
        if (key != null) {
            for (E value : type.getEnumConstants()) {
                if (of(value).equals(key)) {
                    return value;
                }
            }
        }
        String valid = Arrays.stream(type.getEnumConstants()).map(Keys::of).collect(Collectors.joining(", "));
        throw new UnknownKeyException(
                "\"" + key + "\" is not a " + whatItNames + " we know. Use one of: " + valid + ".");
    }

    /** A key that names nothing. Reported to the caller as a bad request, never as a server error. */
    public static final class UnknownKeyException extends IllegalArgumentException {
        public UnknownKeyException(String message) {
            super(message);
        }
    }
}
