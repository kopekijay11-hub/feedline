package ca.feedline.web;

import ca.feedline.dto.ClusterDetailDto;
import ca.feedline.dto.ClusterSummaryDto;
import ca.feedline.service.StoryClusterService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
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

import java.util.List;

@RestController
@RequestMapping("/api/v1/clusters")
@ConditionalOnBean(StoryClusterService.class)
public class ClustersController {

    private final StoryClusterService service;

    public ClustersController(StoryClusterService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List story clusters")
    @ApiResponse(responseCode = "200", description = "list of clusters")
    public ResponseEntity<List<ClusterSummaryDto>> list(
            @Parameter(description = "Hours back to include") @RequestParam(name = "sinceHours", required = false) Integer sinceHours) {
        int h = (sinceHours == null) ? 48 : sinceHours;
        if (h <= 0) {
            h = 48;
        }
        if (h > 168) {
            h = 168;
        }
        return ResponseEntity.ok(service.listClusters(h));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one cluster with members")
    @ApiResponse(responseCode = "200", description = "the cluster")
    @ApiResponse(responseCode = "404", description = "not found", content = @Content(mediaType = "application/problem+json"))
    public ResponseEntity<?> getOne(@PathVariable Long id) {
        ClusterDetailDto dto = service.getCluster(id);
        if (dto == null) {
            ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
            problem.setTitle("Cluster not found");
            problem.setDetail("No cluster with id " + id);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body(problem);
        }
        return ResponseEntity.ok(dto);
    }
}
