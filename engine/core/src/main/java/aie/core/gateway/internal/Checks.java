package aie.core.gateway.internal;

import aie.core.gateway.api.Gateway.Request;

/**
 * Where the trust layer's checks go (blueprint 8.2). The input is checked before a model is chosen, because a check
 * may raise the data class, and every piece before the caller sees it. There is no trust layer yet, so {@link #NONE}
 * passes everything.
 */
public interface Checks {

    /** Passes everything. */
    Checks NONE = new Checks() {
        @Override
        public void input(Request request) {
        }

        @Override
        public String output(String task, String piece) {
            return piece;
        }
    };

    /** Looks at the request before a model is chosen. An exception stops the answer before any model is called. */
    void input(Request request);

    /** Looks at a piece before the caller sees it, and returns what the caller may see. */
    String output(String task, String piece);
}
