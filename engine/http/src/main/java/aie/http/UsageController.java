package aie.http;

import aie.core.metering.api.UsageReports;
import aie.core.tenancy.api.ApiKeyLookup.Owner;
import aie.http.api.UsageApi;
import aie.http.api.model.ModelUsage;
import aie.http.api.model.Money;
import aie.http.api.model.UsageReport;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The usage operation of the contract (M3-6-4): what the application the request runs as used and spent on models in a
 * window of time. Server keys only, as the contract's x-callers say, so a browser's token never gets here (S-10); with
 * On-Behalf-Of, the application is the lower tenant's one the key acts as (blueprint 6.5).
 */
@RestController
class UsageController implements UsageApi {

    /** The currency of every cost (M3-6 decision 1). */
    static final String CURRENCY = "USD";
    /** A cost has six places at least, a millionth of a dollar, as a price per million tokens divided down to one does. */
    private static final int PLACES = 6;

    private final UsageReports reports;
    private final HttpServletRequest request;

    UsageController(UsageReports reports, HttpServletRequest request) {
        this.reports = reports;
        this.request = request;
    }

    /**
     * The window's times are read strictly as RFC 3339, as the contract reads every time (point 46), and refused before
     * anything is read; a window that does not end after it starts is refused as well. The times come back in UTC.
     */
    @Override
    public ResponseEntity<UsageReport> getUsage(@Nullable String from, @Nullable String to, @Nullable String engineVersion,
            @Nullable String traceparent, @Nullable String onBehalfOf) {
        Instant start = time(from, "from");
        Instant end = time(to, "to");
        Owner caller = Objects.requireNonNull((Owner) request.getAttribute(ApiKeyFilter.CALLER), "No caller on a /v1 request");
        UsageReports.Report report;
        try {
            report = reports.report(caller.tenantId(), caller.applicationId(), start, end);
        } catch (IllegalArgumentException wrongWindow) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, wrongWindow.getMessage());
        }
        return ResponseEntity.ok(new UsageReport(utc(report.from()), utc(report.to()), report.inputTokens(), report.outputTokens(),
                money(report.cost()), report.byModel().stream()
                        .map(use -> new ModelUsage(use.model(), use.inputTokens(), use.outputTokens(), money(use.cost())))
                        .toList()));
    }

    /** The time of a query parameter, or null without one. The value itself is not repeated, as no detail repeats one. */
    private static @Nullable Instant time(@Nullable String value, String name) {
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value, RequestDateTimes.RFC_3339).toInstant();
        } catch (DateTimeParseException notRfc3339) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    name + " must be an RFC 3339 date-time with its seconds and its offset, such as 2026-11-01T00:00:00+09:00");
        }
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    /** An exact amount of US dollars, with six places at least and never fewer than it has, so nothing is rounded. */
    static Money money(BigDecimal amount) {
        return new Money(amount.setScale(Math.max(PLACES, amount.scale())).toPlainString(), CURRENCY);
    }
}
