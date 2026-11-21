package aie.adapters.secretsenv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class EnvironmentSecretsTest {

    private static final String NAME = "engine.audit.master.key";

    private static EnvironmentSecrets with(Map<String, String> settings) {
        return new EnvironmentSecrets(name -> Optional.ofNullable(settings.get(name)));
    }

    /** A secret is its setting's base64, decoded; the white space an operator leaves around it does not count. */
    @Test
    void aSecretIsItsSettingDecodedFromBase64() {
        assertThat(with(Map.of(NAME, "AAECAwQ=")).secret(NAME)).hasValueSatisfying(bytes -> assertThat(bytes)
                .containsExactly(0, 1, 2, 3, 4));
        assertThat(with(Map.of(NAME, "  AAECAwQ=\n")).secret(NAME)).hasValueSatisfying(bytes -> assertThat(bytes).hasSize(5));
    }

    @Test
    void aSecretThatIsNotSetIsEmpty() {
        assertThat(with(Map.of()).secret(NAME)).isEmpty();
    }

    /** A value that is not base64 is refused by the variable an operator sets, never with the value itself. */
    @Test
    void aValueThatIsNotBase64IsRefusedByItsVariable() {
        assertThatThrownBy(() -> with(Map.of(NAME, "not*base64*secret")).secret(NAME))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ENGINE_AUDIT_MASTER_KEY is not base64")
                .hasNoCause();
    }

    @Test
    void theVariableOfASecretIsItsNameInCapitalsWithUnderscores() {
        assertThat(EnvironmentSecrets.variable(NAME)).isEqualTo("ENGINE_AUDIT_MASTER_KEY");
    }
}
