package ca.feedline.web;

import ca.feedline.dto.PoisonReplayResponse;
import ca.feedline.dto.PoisonedOutboxPage;
import ca.feedline.service.AdminOutboxService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/outbox/poisoned")
@SecurityRequirement(name = "AdminToken")
public class AdminOutboxController {

    private final AdminOutboxService service;

    public AdminOutboxController(AdminOutboxService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List poisoned outbox events", description = "Returns poisoned (unpublished) outbox rows, newest first.")
    @ApiResponse(responseCode = "200", description = "page of poisoned events")
    @ApiResponse(responseCode = "400", description = "invalid pagination or unknown parameter",
            content = @Content(mediaType = "application/problem+json"))
    @ApiResponse(responseCode = "401", description = "missing or invalid admin token",
            content = @Content(mediaType = "application/problem+json"))
    public ResponseEntity<PoisonedOutboxPage> list(
            @Parameter(description = "Page index (>=0, default 0)")
            @RequestParam(name = "page", required = false, defaultValue = "0") int page,
            @Parameter(description = "Page size (1..100, default 20)")
            @RequestParam(name = "size", required = false, defaultValue = "20") int size,
            @Parameter(description = "Optional topic filter")
            @RequestParam(name = "topic", required = false) String topic) {
        if (page < 0) {
            throw new IllegalArgumentException("page must be >= 0");
        }
        if (size < 1 || size > 100) {
            throw new IllegalArgumentException("size must be between 1 and 100");
        }
        return ResponseEntity.ok(service.listPoisoned(page, size, topic));
    }

    @PostMapping("/{id}/replay")
    @Operation(summary = "Replay a single poisoned outbox event")
    @ApiResponse(responseCode = "200", description = "replayed")
    @ApiResponse(responseCode = "404", description = "not found",
            content = @Content(mediaType = "application/problem+json"))
    @ApiResponse(responseCode = "409", description = "not currently poisoned",
            content = @Content(mediaType = "application/problem+json"))
    public ResponseEntity<PoisonReplayResponse> replayOne(@PathVariable Long id) {
        return ResponseEntity.ok(service.replayOne(id));
    }

    @PostMapping("/replay-batch")
    @Operation(summary = "Replay up to limit oldest poisoned outbox events")
    @ApiResponse(responseCode = "200", description = "batch replayed")
    @ApiResponse(responseCode = "400", description = "invalid limit",
            content = @Content(mediaType = "application/problem+json"))
    public ResponseEntity<PoisonReplayResponse> replayBatch(
            @Parameter(description = "Optional topic filter")
            @RequestParam(name = "topic", required = false) String topic,
            @Parameter(description = "Max events (1..500, default 50)")
            @RequestParam(name = "limit", required = false, defaultValue = "50") int limit) {
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException("limit must be between 1 and 500");
        }
        return ResponseEntity.ok(service.replayBatch(topic, limit));
    }
}
