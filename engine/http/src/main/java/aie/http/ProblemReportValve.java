package aie.http;

import aie.core.Ids;
import java.io.IOException;
import java.io.Writer;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ErrorReportValve;
import org.apache.coyote.ActionCode;
import org.springframework.http.MediaType;
import tools.jackson.databind.json.JsonMapper;

/**
 * Answers what Tomcat refuses by itself, such as a malformed path or an oversized header. Those requests never reach a
 * filter, so this is also where they get their Request-Id. Tomcat's own valve would answer in HTML and name the server.
 */
public class ProblemReportValve extends ErrorReportValve {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Override
    protected void report(Request request, Response response, Throwable throwable) {
        int status = response.getStatus();
        if (status < 400 || response.getContentWritten() > 0 || !response.setErrorReported() || !canWrite(response)) {
            return;
        }
        try {
            if (response.getHeader(RequestIdFilter.HEADER) == null) {
                response.setHeader(RequestIdFilter.HEADER, Ids.next(RequestIdFilter.PREFIX));
            }
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            Writer writer = response.getReporter();
            if (writer != null) {
                writer.write(JSON.writeValueAsString(Problems.of(status, null)));
                response.finishResponse();
            }
        } catch (IOException | IllegalStateException e) {
            // The connection has gone, so nobody is left to answer.
        }
    }

    private static boolean canWrite(Response response) {
        AtomicBoolean allowed = new AtomicBoolean(false);
        response.getCoyoteResponse().action(ActionCode.IS_IO_ALLOWED, allowed);
        return allowed.get();
    }
}
