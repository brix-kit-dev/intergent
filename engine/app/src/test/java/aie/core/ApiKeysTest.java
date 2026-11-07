package aie.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import aie.core.tenancy.api.ApiKeys;
import aie.core.tenancy.api.ApiKeys.Parsed;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ApiKeysTest {

    private static final String SECRET = "0123456789ABCDEFGHIJabcdefghijKLMNOPQRST";

    @ParameterizedTest
    @CsvSource({"live, jp1", "test, dev", "live, a", "test, abcdefg8"})
    void aKeyCarriesItsEnvironmentAndCell(String environment, String cell) {
        String key = ApiKeys.issue(environment, cell);

        assertThat(key).matches("aie_" + environment + "_" + cell + "_[0-9A-Za-z]{40}");
        assertThat(ApiKeys.parse(key)).contains(new Parsed(environment, cell));
    }

    @Test
    void everyKeyIsNew() {
        Set<String> keys = new HashSet<>();
        IntStream.range(0, 10_000).forEach(i -> keys.add(ApiKeys.issue("test", "jp1")));

        assertThat(keys).hasSize(10_000);
    }

    @Test
    void theSecretUsesEveryDigit() {
        Set<Character> used = new HashSet<>();
        IntStream.range(0, 1_000).forEach(i -> ApiKeys.issue("test", "jp1").substring("aie_test_jp1_".length())
                .chars().forEach(c -> used.add((char) c)));

        assertThat(used).hasSize(62);
    }

    @ParameterizedTest
    @CsvSource({"staging, jp1", "live, JP1", "live, jp_1", "live, abcdefghi", "live, ''"})
    void noKeyIsMadeThatCouldNotBeRead(String environment, String cell) {
        assertThatThrownBy(() -> ApiKeys.issue(environment, cell)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "",
        "aie_live_jp1",
        "key_live_jp1_" + SECRET,
        "aie_prod_jp1_" + SECRET,
        "aie_live_JP1_" + SECRET,
        "aie_live_jp_1_" + SECRET,
        "aie_live_abcdefghi_" + SECRET,
        "aie_live_jp1_" + SECRET + "U",
        "aie_live_jp1_0123456789ABCDEFGHIJabcdefghijKLMNOPQRS",
        "aie_live_jp1_0123456789ABCDEFGHIJabcdefghijKLMNOPQRS_",
        "aie_live_jp1_" + SECRET + "\n",
        " aie_live_jp1_" + SECRET,
        "AIE_live_jp1_" + SECRET
    })
    void anythingElseReadsAsNoKey(String text) {
        assertThat(ApiKeys.parse(text)).isEmpty();
    }

    @Test
    void theHashIsSha256InHex() {
        assertThat(ApiKeys.hash("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void twoKeysNeverShareAHash() {
        assertThat(ApiKeys.hash(ApiKeys.issue("test", "jp1"))).isNotEqualTo(ApiKeys.hash(ApiKeys.issue("test", "jp1")));
    }
}
