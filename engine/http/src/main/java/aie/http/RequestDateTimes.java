package aie.http;

import java.time.OffsetDateTime;
import java.time.chrono.IsoChronology;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.util.Locale;
import org.springframework.boot.jackson.JacksonComponent;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/**
 * Reads a date and time in a request body only in the contract's form, an RFC 3339 string such as 2026-01-01T00:00:00Z
 * or 2026-01-01T09:00:00+09:00 (point 46). Jackson's own reader trims the text first, so a NUL or a space at either end
 * passed; it read an empty or blank string as no value at all, which made a key that never expires; and it took a
 * number of seconds and an offset without a colon. Any other form is refused like any value the model cannot read, so
 * the 400 names the field and shows the form. A JSON null is still no value, as for any field the request may leave out.
 */
@JacksonComponent
public class RequestDateTimes extends ValueDeserializer<OffsetDateTime> {

    /**
     * RFC 3339 section 5.6: a year of four digits, the seconds always, a fraction if any, then Z or an offset with a colon.
     * T and Z may be lower case. A date that does not exist, such as 30 February, is refused rather than moved. The admin
     * role reads the times of its options so as well (M3-5-2).
     */
    public static final DateTimeFormatter RFC_3339 = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendValue(ChronoField.YEAR, 4)
            .appendLiteral('-')
            .appendValue(ChronoField.MONTH_OF_YEAR, 2)
            .appendLiteral('-')
            .appendValue(ChronoField.DAY_OF_MONTH, 2)
            .appendLiteral('T')
            .appendValue(ChronoField.HOUR_OF_DAY, 2)
            .appendLiteral(':')
            .appendValue(ChronoField.MINUTE_OF_HOUR, 2)
            .appendLiteral(':')
            .appendValue(ChronoField.SECOND_OF_MINUTE, 2)
            .optionalStart()
            .appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true)
            .optionalEnd()
            .appendOffset("+HH:MM", "Z")
            .toFormatter(Locale.ROOT)
            .withChronology(IsoChronology.INSTANCE)
            .withResolverStyle(ResolverStyle.STRICT);

    @Override
    public OffsetDateTime deserialize(JsonParser parser, DeserializationContext context) {
        if (!parser.hasToken(JsonToken.VALUE_STRING)) {
            return (OffsetDateTime) context.handleUnexpectedToken(OffsetDateTime.class, parser);
        }
        String text = parser.getString();
        try {
            return OffsetDateTime.parse(text, RFC_3339);
        } catch (DateTimeParseException e) {
            return (OffsetDateTime) context.handleWeirdStringValue(OffsetDateTime.class, text, "not an RFC 3339 date and time");
        }
    }
}
