package ca.feedline.health;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/sources/scorecard")
public class SourcesScorecardController {

    private final SourceHealthService service;

    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "injected Spring service")
    public SourcesScorecardController(SourceHealthService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<SourceScorecard>> scorecards() {
        return ResponseEntity.ok(service.scorecards());
    }
}
