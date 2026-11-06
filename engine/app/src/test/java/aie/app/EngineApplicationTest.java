package aie.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EngineApplicationTest {

    @Test
    void aMissingRoleIsAUsageError() {
        assertThat(EngineApplication.launch(new String[0])).isEqualTo(EngineApplication.EXIT_USAGE);
    }

    @Test
    void anUnknownRoleIsAUsageError() {
        assertThat(EngineApplication.launch(new String[] {"--role=nope"})).isEqualTo(EngineApplication.EXIT_USAGE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"worker", "admin"})
    void rolesWithoutAnImplementationSaySo(String role) {
        assertThat(EngineApplication.launch(new String[] {"--role=" + role}))
                .isEqualTo(EngineApplication.EXIT_NOT_IMPLEMENTED);
    }
}
