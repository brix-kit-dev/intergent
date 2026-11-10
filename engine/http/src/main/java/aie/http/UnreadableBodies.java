package aie.http;

import java.math.BigInteger;
import java.time.temporal.Temporal;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.core.exc.InputCoercionException;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.exc.PropertyBindingException;
import tools.jackson.databind.exc.ValueInstantiationException;

/**
 * The detail of a 400 for a body that the generated model cannot read. It names the field and says what the contract
 * wants there, from the model's own types. It never repeats what the caller sent, nor what the parser said, because
 * the parser's words name the engine's classes.
 */
final class UnreadableBodies {

    private static final Set<Class<?>> WHOLE_NUMBERS = Set.of(
            byte.class, short.class, int.class, long.class, Byte.class, Short.class, Integer.class, Long.class, BigInteger.class);

    private UnreadableBodies() {
    }

    /** {@code cause} is what Spring found under the failure. Spring gives none when there was no body. */
    static String detail(@Nullable Throwable cause) {
        if (cause == null) {
            return "The request needs a JSON body";
        }
        // Only a strict mapper refuses a field it does not know, and that field's name is the caller's own.
        if (cause instanceof PropertyBindingException) {
            return "The body has a field that this request does not take";
        }
        List<JacksonException.Reference> path = cause instanceof JacksonException jackson ? jackson.getPath() : List.of();
        // The parser reports a number too large for its field like broken JSON, so it comes first.
        if (cause instanceof InputCoercionException && !path.isEmpty()) {
            return field(path) + " is out of range";
        }
        if (cause instanceof StreamReadException) {
            return "The body is not valid JSON";
        }
        if (!(cause instanceof JacksonException)) {
            return "The body cannot be read";
        }
        if (path.isEmpty()) {
            return "The body must be a single JSON object";
        }
        String expected = expected(cause instanceof MismatchedInputException mismatch ? mismatch.getTargetType()
                : cause instanceof ValueInstantiationException instantiation ? instantiation.getType().getRawClass()
                : null);
        return expected == null ? field(path) + " has a value of the wrong type" : field(path) + " must be " + expected;
    }

    /** The field as the caller wrote it, such as scopes[1]. The keys of a map are the caller's own, so they stay out. */
    private static String field(List<JacksonException.Reference> path) {
        StringBuilder field = new StringBuilder();
        for (JacksonException.Reference step : path) {
            if (step.from() instanceof Map<?, ?> || step.from() instanceof Class<?> type && Map.class.isAssignableFrom(type)) {
                return "each value in " + (field.isEmpty() ? "the body" : field);
            }
            if (step.getPropertyName() == null) {
                field.append('[').append(step.getIndex()).append(']');
            } else {
                field.append(field.isEmpty() ? "" : ".").append(step.getPropertyName());
            }
        }
        return field.toString();
    }

    /** What a value of this type looks like in JSON, or null when the type does not say. */
    private static @Nullable String expected(@Nullable Class<?> type) {
        if (type == null) {
            return null;
        }
        if (type.isEnum()) {
            // A generated enum gives its JSON value as its string.
            return "one of: " + Arrays.stream(type.getEnumConstants()).map(String::valueOf).collect(Collectors.joining(", "));
        }
        if (CharSequence.class.isAssignableFrom(type) || type == char.class || type == Character.class) {
            return "a string";
        }
        if (type == boolean.class || type == Boolean.class) {
            return "true or false";
        }
        if (WHOLE_NUMBERS.contains(type)) {
            return "a whole number";
        }
        if (type.isPrimitive() || Number.class.isAssignableFrom(type)) {
            return "a number";
        }
        if (Temporal.class.isAssignableFrom(type)) {
            return "a date and time such as 2026-01-01T00:00:00Z";
        }
        if (type.isArray() || Collection.class.isAssignableFrom(type)) {
            return "an array";
        }
        return "an object";
    }
}
