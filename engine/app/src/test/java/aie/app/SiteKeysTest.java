package aie.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import aie.core.tenancy.api.ApiKeys;
import aie.core.tenancy.api.SiteKeys;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** The site key format and the origins a site key may list, without the database. AdminRoleTest covers issuing them. */
class SiteKeysTest {

    @Test
    void aSiteKeyNamesItsEnvironmentAndCell() {
        String key = SiteKeys.issue("live", "jp1");

        assertThat(key).matches("aie_site_live_jp1_[0-9A-Za-z]{40}");
        assertThat(SiteKeys.parse(key)).contains(new ApiKeys.Parsed("live", "jp1"));
        assertThat(SiteKeys.issue("test", "dev")).startsWith("aie_site_test_dev_");
    }

    @Test
    void twoSiteKeysDiffer() {
        assertThat(SiteKeys.issue("test", "jp1")).isNotEqualTo(SiteKeys.issue("test", "jp1"));
    }

    /** One kind of key never passes for the other, so a server key pasted into a page is not a site key. */
    @Test
    void aSiteKeyIsNotAnApiKeyAndAnApiKeyIsNotASiteKey() {
        assertThat(ApiKeys.parse(SiteKeys.issue("test", "jp1"))).isEmpty();
        assertThat(SiteKeys.parse(ApiKeys.issue("test", "jp1"))).isEmpty();
        assertThat(SiteKeys.parse(ApiKeys.issue("test", "site"))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "aie_site_live_jp1_", "aie_site_prod_jp1_0123456789012345678901234567890123456789",
        "aie_site_live_JP1_0123456789012345678901234567890123456789", "aie_site_live_jp1_012345678901234567890123456789012345678",
        "aie_site_live_jp1_0123456789012345678901234567890123456789x", "aie_site_live_jp1_012345678901234567890123456789012345678_"})
    void anythingElseIsNotASiteKey(String key) {
        assertThat(SiteKeys.parse(key)).isEmpty();
    }

    @Test
    void aSiteKeyIsHashedLikeAnApiKey() {
        String key = SiteKeys.issue("test", "jp1");

        assertThat(SiteKeys.hash(key)).isEqualTo(ApiKeys.hash(key)).matches("[0-9a-f]{64}");
    }

    /** The form a browser sends in the Origin header: lowercase, no default port, no trailing slash. */
    @ParameterizedTest
    @CsvSource({
        "https://shop.example.com, https://shop.example.com",
        "HTTPS://Shop.Example.COM/, https://shop.example.com",
        "https://shop.example.com:443, https://shop.example.com",
        "https://shop.example.com:8443, https://shop.example.com:8443",
        "https://203.0.113.5, https://203.0.113.5",
        "https://xn--eckwd4c7c.xn--zckzah, https://xn--eckwd4c7c.xn--zckzah",
        "https://localhost, https://localhost",
        "http://localhost:3000, http://localhost:3000",
        "http://localhost:80, http://localhost",
        "http://127.0.0.1:8080, http://127.0.0.1:8080"
    })
    void anOriginIsWrittenAsABrowserSendsIt(String value, String origin) {
        assertThat(SiteKeys.origin(value)).contains(origin);
    }

    /** Plain http only on the developer's own machine; nothing after the port; no wildcards; ASCII hosts only. */
    @ParameterizedTest
    @ValueSource(strings = {"http://shop.example.com", "https://shop.example.com/path", "https://shop.example.com?x=1",
        "https://shop.example.com#top", "https://user@shop.example.com", "https://*.example.com", "https://shop_example.com",
        "https://ショップ.example", "https://[::1]", "http://[::1]:3000", "ftp://shop.example.com", "shop.example.com",
        "//shop.example.com", "https://", "", " https://shop.example.com", "https://shop.example.com:0",
        "https://shop.example.com:65536", "https://shop.example.com.", "https://-shop.example.com", "http://localhost.example.com"})
    void anythingElseIsNotAnOriginASiteKeyMayList(String value) {
        assertThat(SiteKeys.origin(value)).isEmpty();
    }

    @Test
    void anOriginHasAtMost255Characters() {
        String longest = "https://" + "a".repeat(SiteKeys.MAX_ORIGIN - "https://.com".length()) + ".com";
        assertThat(longest).hasSize(SiteKeys.MAX_ORIGIN);

        assertThat(SiteKeys.origin(longest)).contains(longest);
        assertThat(SiteKeys.origin(longest.replace("https://", "https://a"))).isEmpty();
        assertThat(SiteKeys.origin(null)).isEmpty();
    }

    @Test
    void theOriginsKeepTheirOrderWithoutRepeats() {
        assertThat(SiteKeys.origins(List.of("https://b.example", "https://a.example", "HTTPS://B.example/", "https://a.example:443")))
                .containsExactly("https://b.example", "https://a.example");
    }

    @Test
    void aSiteKeyListsOneToTenOrigins() {
        List<String> ten = IntStream.rangeClosed(1, 10).mapToObj(i -> "https://shop" + i + ".example").toList();
        List<String> eleven = IntStream.rangeClosed(1, 11).mapToObj(i -> "https://shop" + i + ".example").toList();

        assertThat(SiteKeys.origins(ten)).hasSize(10);
        assertThatThrownBy(() -> SiteKeys.origins(eleven))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A site key lists 1 to 10 origins, not 11");
        assertThatThrownBy(() -> SiteKeys.origins(Collections.emptyList()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A site key lists 1 to 10 origins, not 0");
    }

    @Test
    void theFirstBadOriginIsNamed() {
        assertThatThrownBy(() -> SiteKeys.origins(List.of("https://shop.example.com", "http://shop.example.com")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Not an origin a site key may list: http://shop.example.com. "
                        + "Write https://host or https://host:port; http is only for localhost and 127.0.0.1");
    }
}
