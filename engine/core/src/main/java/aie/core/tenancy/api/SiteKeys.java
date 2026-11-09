package aie.core.tenancy.api;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The site keys of blueprint 6.5, such as {@code aie_site_live_jp1_...}. A browser trades one for an anonymous token,
 * and only from an origin the key lists. A site key sits in the host's pages, so it is public, but like an API key only
 * its hash is stored, because blueprint 7.6 looks it up by hash. The origin check stops other sites from embedding the
 * key, not scripts, which is why anonymous tokens get the lowest rights.
 */
public final class SiteKeys {

    /** The most origins one site key may list. */
    public static final int MAX_ORIGINS = 10;
    /** The longest origin, in characters. A host name alone may have 253. */
    public static final int MAX_ORIGIN = 255;

    private static final Pattern FORMAT = Pattern.compile(
            "aie_site_(?<environment>live|test)_(?<cell>[a-z0-9]{1,8})_[0-9A-Za-z]{40}");
    /** A DNS name or an IPv4 address in ASCII. Other forms, such as IPv6 or an unencoded international name, are refused. */
    private static final Pattern HOST = Pattern.compile("[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*");
    /** Plain http only for a developer's own machine. */
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1");

    private SiteKeys() {
    }

    /** A new site key for an application of this environment in this cell. */
    public static String issue(String environment, String cellCode) {
        String issued = "aie_site_" + environment + "_" + cellCode + "_" + ApiKeys.secret();
        if (parse(issued).isEmpty()) {
            throw new IllegalArgumentException("No site key can be made for environment " + environment + " in cell " + cellCode);
        }
        return issued;
    }

    /** What a site key says about itself, read without the database, or nothing for anything else. */
    public static Optional<ApiKeys.Parsed> parse(String key) {
        Matcher matcher = FORMAT.matcher(key);
        return matcher.matches() ? Optional.of(new ApiKeys.Parsed(matcher.group("environment"), matcher.group("cell"))) : Optional.empty();
    }

    /** The value the database keeps, the same SHA-256 as for API keys. */
    public static String hash(String key) {
        return ApiKeys.hash(key);
    }

    /**
     * The origin as a browser writes it in the Origin header, such as {@code https://shop.example.com}, or empty when
     * it is not one a site key may list. It must be https, except for http on localhost and 127.0.0.1. The scheme and
     * host are lowercased, a default port and a trailing slash are dropped, and nothing else may follow the port.
     */
    public static Optional<String> origin(String value) {
        if (value == null || value.length() > MAX_ORIGIN) {
            return Optional.empty();
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        if (!HOST.matcher(host).matches() || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null || !(path.isEmpty() || path.equals("/"))) {
            return Optional.empty();
        }
        if (!scheme.equals("https") && !(scheme.equals("http") && LOCAL_HOSTS.contains(host))) {
            return Optional.empty();
        }
        int port = uri.getPort();
        if (port == 0 || port > 65535) {
            return Optional.empty();
        }
        boolean defaultPort = port == -1 || port == (scheme.equals("https") ? 443 : 80);
        return Optional.of(scheme + "://" + host + (defaultPort ? "" : ":" + port));
    }

    /**
     * The origins in the form {@link #origin} gives, in order and without repeats.
     *
     * @throws IllegalArgumentException naming the first value that is not an origin, or when there are none or too many
     */
    public static List<String> origins(List<String> values) {
        Set<String> origins = new LinkedHashSet<>();
        for (String value : values) {
            origins.add(origin(value).orElseThrow(() -> new IllegalArgumentException("Not an origin a site key may list: "
                    + value + ". Write https://host or https://host:port; http is only for localhost and 127.0.0.1")));
        }
        if (origins.isEmpty() || origins.size() > MAX_ORIGINS) {
            throw new IllegalArgumentException("A site key lists 1 to " + MAX_ORIGINS + " origins, not " + origins.size());
        }
        return List.copyOf(origins);
    }
}
