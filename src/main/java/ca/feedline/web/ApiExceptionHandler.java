package ca.feedline.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@ControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(UnknownQueryParameterException.class)
    public org.springframework.http.ResponseEntity<ProblemDetail> handleUnknown(UnknownQueryParameterException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Unknown query parameter");
        problem.setDetail("Unknown query parameter: " + ex.getParameter());
        problem.setProperty("parameter", ex.getParameter());
        return org.springframework.http.ResponseEntity.badRequest()
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public org.springframework.http.ResponseEntity<ProblemDetail> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Malformed request parameter");
        String name = ex.getName();
        problem.setDetail("Malformed value for parameter: " + name);
        problem.setProperty("parameter", name);
        return org.springframework.http.ResponseEntity.badRequest()
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public org.springframework.http.ResponseEntity<ProblemDetail> handleIllegal(IllegalArgumentException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Bad request");
        problem.setDetail(ex.getMessage());
        return org.springframework.http.ResponseEntity.badRequest()
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }
    @ExceptionHandler(ca.feedline.web.OutboxEventNotFoundException.class)
    public org.springframework.http.ResponseEntity<org.springframework.http.ProblemDetail> handleNotFound(ca.feedline.web.OutboxEventNotFoundException ex) {
        org.springframework.http.ProblemDetail problem = org.springframework.http.ProblemDetail.forStatus(org.springframework.http.HttpStatus.NOT_FOUND);
        problem.setTitle("Outbox event not found");
        problem.setDetail(ex.getMessage());
        return org.springframework.http.ResponseEntity.status(org.springframework.http.HttpStatus.NOT_FOUND)
                .contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    @ExceptionHandler(ca.feedline.web.OutboxEventNotPoisonedException.class)
    public org.springframework.http.ResponseEntity<org.springframework.http.ProblemDetail> handleConflict(ca.feedline.web.OutboxEventNotPoisonedException ex) {
        org.springframework.http.ProblemDetail problem = org.springframework.http.ProblemDetail.forStatus(org.springframework.http.HttpStatus.CONFLICT);
        problem.setTitle("Outbox event not poisoned");
        problem.setDetail(ex.getMessage());
        return org.springframework.http.ResponseEntity.status(org.springframework.http.HttpStatus.CONFLICT)
                .contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }
}
