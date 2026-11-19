package ca.feedline.health;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

@RestController
@RequestMapping("/api/v1/sources/health")
public class SourcesHealthController {

    private final SourceHealthService service;

    public SourcesHealthController(SourceHealthService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<SourcesHealth> aggregate() {
        SourcesHealth health = service.aggregate();
        HttpStatus status = "UP".equals(health.status()) ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(health);
    }

    @GetMapping("/{sourceId}")
    public ResponseEntity<Object> one(@PathVariable Long sourceId) {
        Optional<SourceHealthDetail> found = service.findById(sourceId);
        if (found.isPresent()) {
            return ResponseEntity.ok(found.get());
        }
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Source not found");
        problem.setDetail("No source with id " + sourceId);
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }
}
