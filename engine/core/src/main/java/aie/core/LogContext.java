package aie.core;

/**
 * The keys of the log context, SLF4J's MDC, that the engine's lines carry. One place names them, so that what the
 * interface layer puts there is what the core reads back, such as the audit's request ID (M3-5-1).
 */
public final class LogContext {

    /** The ID of the request a line belongs to, as its Request-Id header gives it (blueprint 6.2). */
    public static final String REQUEST_ID = "request_id";

    private LogContext() {
    }
}
