package aie.app;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** The api role: serves requests from the interface layer. */
@SpringBootApplication(scanBasePackages = "aie.http")
class ApiRole {

    static ConfigurableApplicationContext start(String... args) {
        return new SpringApplicationBuilder(ApiRole.class).web(WebApplicationType.SERVLET).run(args);
    }
}
