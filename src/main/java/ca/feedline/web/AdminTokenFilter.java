package ca.feedline.web;

import ca.feedline.config.FeedProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ProblemDetail;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

@Component
public class AdminTokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AdminTokenFilter.class);

    private final FeedProperties feedProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Spring injection")
    public AdminTokenFilter(FeedProperties feedProperties) {
        this.feedProperties = feedProperties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path == null || !path.startsWith("/api/v1/admin/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        FeedProperties.Admin admin = feedProperties.getAdmin();
        String remote = request.getRemoteAddr();
        String path = request.getRequestURI();
        if (admin == null || !admin.isEnabled() || admin.getApiKey() == null || admin.getApiKey().isBlank()) {
            log.warn("Unauthorized admin access attempt from {} to {}", remote, path);
            writeProblem(response, HttpStatus.UNAUTHORIZED, "Admin API disabled or token not configured");
            return;
        }
        String headerName = admin.getHeaderName() == null || admin.getHeaderName().isBlank()
                ? "X-Admin-Token" : admin.getHeaderName();
        String supplied = request.getHeader(headerName);
        byte[] expectedBytes = admin.getApiKey().getBytes(StandardCharsets.UTF_8);
        byte[] suppliedBytes = supplied == null ? new byte[0] : supplied.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expectedBytes, suppliedBytes)) {
            log.warn("Unauthorized admin access attempt from {} to {}", remote, path);
            writeProblem(response, HttpStatus.UNAUTHORIZED, "Missing or invalid admin token");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private void writeProblem(HttpServletResponse response, HttpStatus status, String detail) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setTitle(status == HttpStatus.UNAUTHORIZED ? "Unauthorized" : "Forbidden");
        problem.setDetail(detail);
        objectMapper.writeValue(response.getWriter(), problem);
    }
}
