package ca.feedline.web;

import ca.feedline.config.FeedProperties;
import ca.feedline.dto.ItemDto;
import ca.feedline.dto.ItemsPage;
import ca.feedline.service.ItemQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1/items")
public class ItemsController {

    private final ItemQueryService queryService;
    private final FeedProperties.Items itemsConfig;

    public ItemsController(ItemQueryService queryService, FeedProperties feedProperties) {
        this.queryService = queryService;
        FeedProperties.Items configured = feedProperties == null ? new FeedProperties().getItems() : feedProperties.getItems();
        this.itemsConfig = configured == null ? new FeedProperties().getItems() : configured;
    }

    @GetMapping
    @Operation(
            summary = "List items (keyset pagination)",
            description = "Keyset pagination on (fetched_at, id), default sort fetched_at DESC, id DESC. "
                    + "Page size capped at feed.items.max-page-size (default 100, configured default feed.items.default-page-size 20). "
                    + "Response carries items, nextCursor, total, hasMore. "
                    + "Filters: source (source id), q (full-text over title+summary tsvector), "
                    + "published date range from/to. Null policy: items with null published_at are "
                    + "EXCLUDED from date-range results (from/to); they appear in the default listing "
                    + "in ingestion (fetched_at) order.")
    @ApiResponse(responseCode = "200", description = "page of items")
    @ApiResponse(responseCode = "400", description = "bad request (unknown parameter, bad cursor, oversized page)",
            content = @Content(mediaType = "application/problem+json"))
    public ResponseEntity<Object> list(
            @Parameter(description = "Filter by source id") @RequestParam(name = "source", required = false) Long source,
            @Parameter(description = "Full-text search over title and summary") @RequestParam(name = "q", required = false) String q,
            @Parameter(description = "Published-at lower bound (inclusive). Items with null published_at are excluded when set.")
            @RequestParam(name = "from", required = false) Instant from,
            @Parameter(description = "Published-at upper bound (inclusive). Items with null published_at are excluded when set.")
            @RequestParam(name = "to", required = false) Instant to,
            @Parameter(description = "Fetched-at lower bound (inclusive), point-in-time ingest window")
            @RequestParam(name = "fetchedFrom", required = false) Instant fetchedFrom,
            @Parameter(description = "Fetched-at upper bound (inclusive), point-in-time ingest window")
            @RequestParam(name = "fetchedTo", required = false) Instant fetchedTo,
            @Parameter(description = "Opaque keyset cursor from a previous page") @RequestParam(name = "cursor", required = false) String cursor,
            @Parameter(description = "Page size (max feed.items.max-page-size, default feed.items.default-page-size)")
            @RequestParam(name = "size", required = false) Integer size) {
        int pageSize = (size == null) ? itemsConfig.getDefaultPageSize() : size;
        if (pageSize <= 0) {
            pageSize = itemsConfig.getDefaultPageSize();
        }
        if (pageSize > itemsConfig.getMaxPageSize()) {
            pageSize = itemsConfig.getMaxPageSize();
        }
        try {
            ItemsPage page = queryService.list(source, q, from, to, fetchedFrom, fetchedTo, cursor, pageSize);
            return ResponseEntity.ok(page);
        } catch (IllegalArgumentException ex) {
            ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
            problem.setTitle("Bad request");
            problem.setDetail(ex.getMessage());
            return ResponseEntity.badRequest()
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body(problem);
        }
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one item by id", description = "Returns the item, or 404 problem+json. A malformed id is a 400.")
    @ApiResponse(responseCode = "200", description = "the item")
    @ApiResponse(responseCode = "404", description = "not found", content = @Content(mediaType = "application/problem+json"))
    @ApiResponse(responseCode = "400", description = "malformed id", content = @Content(mediaType = "application/problem+json"))
    public ResponseEntity<?> getOne(@PathVariable Long id) {
        ItemDto item = queryService.findById(id);
        if (item == null) {
            ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
            problem.setTitle("Item not found");
            problem.setDetail("No item with id " + id);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body(problem);
        }
        return ResponseEntity.ok(item);
    }
}
