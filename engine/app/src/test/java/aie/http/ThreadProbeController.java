package aie.http;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Test-only endpoint that reports what kind of thread served the request. */
@RestController
class ThreadProbeController {

    @GetMapping("/test/thread")
    Map<String, Boolean> thread() {
        return Map.of("virtual", Thread.currentThread().isVirtual());
    }
}
