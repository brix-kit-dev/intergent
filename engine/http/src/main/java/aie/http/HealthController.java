package aie.http;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Liveness probe for operators. It sits outside the public /v1 contract. */
@RestController
class HealthController {

    @GetMapping("/healthz")
    Map<String, String> health() {
        return Map.of("status", "ok");
    }
}
