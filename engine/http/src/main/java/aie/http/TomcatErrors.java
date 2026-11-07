package aie.http;

import org.apache.catalina.core.StandardHost;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.stereotype.Component;

/** Puts {@link ProblemReportValve} in place of the valve Tomcat would use. */
@Component
class TomcatErrors implements WebServerFactoryCustomizer<TomcatServletWebServerFactory> {

    @Override
    public void customize(TomcatServletWebServerFactory factory) {
        factory.addContextCustomizers(context ->
                ((StandardHost) context.getParent()).setErrorReportValveClass(ProblemReportValve.class.getName()));
    }
}
