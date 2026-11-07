package aie.http;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Answers the failures that never reach Spring MVC: a filter that throws or calls sendError. The container forwards
 * those here after RequestIdFilter has finished, so the ID comes back from the response for the log line.
 */
@RestController
class ProblemErrorController implements ErrorController {

    private static final Logger LOG = LoggerFactory.getLogger(ProblemErrorController.class);

    @RequestMapping("/error")
    ResponseEntity<Object> error(HttpServletRequest request, HttpServletResponse response) {
        // With no status the container did not send this here: a caller asked for /error, and nothing lives there.
        if (!(request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) instanceof Integer status)) {
            return Problems.response(HttpStatus.NOT_FOUND.value(), null, HttpHeaders.EMPTY);
        }
        if (request.getAttribute(RequestDispatcher.ERROR_EXCEPTION) instanceof Throwable failure) {
            MDC.put(RequestIdFilter.LOG_KEY, response.getHeader(RequestIdFilter.HEADER));
            try {
                LOG.error("The request failed", failure);
            } finally {
                MDC.remove(RequestIdFilter.LOG_KEY);
            }
        }
        return Problems.response(status, null, HttpHeaders.EMPTY);
    }
}
