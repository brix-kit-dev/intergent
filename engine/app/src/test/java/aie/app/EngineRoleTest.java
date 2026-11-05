package aie.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class EngineRoleTest {

    @ParameterizedTest
    @CsvSource({"api,API", "worker,WORKER", "migrate,MIGRATE", "admin,ADMIN"})
    void parsesEveryRole(String argument, EngineRole expected) {
        assertThat(EngineRole.parse(new String[] {"--role=" + argument})).isEqualTo(expected);
    }

    @Test
    void findsTheRoleAmongOtherArguments() {
        assertThat(EngineRole.parse(new String[] {"--server.port=0", "--role=api"})).isEqualTo(EngineRole.API);
    }

    @Test
    void refusesAMissingRole() {
        assertThatThrownBy(() -> EngineRole.parse(new String[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--role is required")
                .hasMessageContaining("api|worker|migrate|admin");
    }

    @Test
    void refusesAnUnknownRole() {
        assertThatThrownBy(() -> EngineRole.parse(new String[] {"--role=nope"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown role: nope");
    }

    @Test
    void refusesUppercase() {
        assertThatThrownBy(() -> EngineRole.parse(new String[] {"--role=API"}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesTwoRoles() {
        assertThatThrownBy(() -> EngineRole.parse(new String[] {"--role=api", "--role=worker"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("more than once");
    }
}
