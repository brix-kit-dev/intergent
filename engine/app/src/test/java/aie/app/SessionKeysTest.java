package aie.app;

import static org.assertj.core.api.Assertions.assertThat;

import aie.core.tenancy.api.SessionTokens;
import aie.core.tenancy.api.SessionTokens.IdentityLevel;
import aie.core.tenancy.api.SessionTokens.Session;
import aie.core.tenancy.internal.SignedSessionTokens;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The session key settings on their own, without starting the api. ApiRoleTest covers the refusals. */
class SessionKeysTest {

    private static final Session SESSION = new Session("ten_A", "app_A", "eu_A", IdentityLevel.VERIFIED, List.of(), Map.of(), null);

    /** A key pasted with a space or a line break around it still works, instead of failing as "not base64". */
    @Test
    void aKeyWithSpaceAroundItWorks() {
        String key = Base64.getEncoder().encodeToString(TestApi.SESSION_KEY);

        SessionTokens tokens = SessionKeys.tokens(RoleSettings.read("--engine.session.key= " + key + "\n"), Clock.systemUTC());

        assertThat(new SignedSessionTokens(Clock.systemUTC(), TestApi.SESSION_KEY, List.of()).verify(tokens.issue(SESSION).token()))
                .isPresent();
    }
}
