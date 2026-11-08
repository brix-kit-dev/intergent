package aie.http;

import jakarta.servlet.http.HttpServletRequest;

/** The public API of blueprint 6.3 lives under /v1. The filters that guard it share this one test. */
final class PublicApi {

    private static final String ROOT = "/v1";

    private PublicApi() {
    }

    /** The servlet path is decoded, so an escaped path cannot slip past this check. */
    static boolean contains(HttpServletRequest request) {
        String path = request.getServletPath();
        return path.equals(ROOT) || path.startsWith(ROOT + "/");
    }
}
