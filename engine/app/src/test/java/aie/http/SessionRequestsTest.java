package aie.http;

import static org.assertj.core.api.Assertions.assertThat;

import aie.http.api.model.CreateSessionRequest;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import org.junit.jupiter.api.Test;

/**
 * The limits that {@link SessionRequests} checks are the contract's. The model generated from the contract carries
 * them as annotations, which nothing enforces at run time, so this test holds the two together. Context keys have no
 * annotation, because the generator ignores {@code propertyNames}; guard's SessionLimitsTest pins those in the contract.
 */
class SessionRequestsTest {

    @Test
    void theHostsIdHasTheContractsLength() throws Exception {
        Size size = getter("getExternalUserId").getAnnotation(Size.class);

        assertThat(size.min()).isEqualTo(1);
        assertThat(size.max()).isEqualTo(SessionRequests.MAX_EXTERNAL_ID);
    }

    @Test
    void scopesHaveTheContractsLimits() throws Exception {
        AnnotatedType scope = typeArgument("getScopes", 0);

        assertThat(getter("getScopes").getAnnotation(Size.class).max()).isEqualTo(SessionRequests.MAX_SCOPES);
        assertThat(scope.getAnnotation(Size.class).min()).isEqualTo(1);
        assertThat(scope.getAnnotation(Size.class).max()).isEqualTo(SessionRequests.MAX_SCOPE);
        assertThat(scope.getAnnotation(Pattern.class).regexp()).isEqualTo("^" + SessionRequests.NAME.pattern() + "$");
    }

    @Test
    void contextHasTheContractsLimits() throws Exception {
        assertThat(getter("getContext").getAnnotation(Size.class).max()).isEqualTo(SessionRequests.MAX_CONTEXT);
        assertThat(typeArgument("getContext", 1).getAnnotation(Size.class).max()).isEqualTo(SessionRequests.MAX_CONTEXT_VALUE);
    }

    private static java.lang.reflect.Method getter(String name) throws NoSuchMethodException {
        return CreateSessionRequest.class.getMethod(name);
    }

    /** An element type of a getter's List or Map, with its type-use annotations. */
    private static AnnotatedType typeArgument(String getter, int index) throws NoSuchMethodException {
        return ((AnnotatedParameterizedType) getter(getter).getAnnotatedReturnType()).getAnnotatedActualTypeArguments()[index];
    }
}
