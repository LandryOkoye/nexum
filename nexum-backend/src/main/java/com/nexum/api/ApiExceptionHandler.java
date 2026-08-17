package com.nexum.api;

import java.util.LinkedHashMap;
import java.util.Map;

import com.nexum.goal.GoalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns the API's failure cases into plain, consistent responses.
 *
 * <p>Exists so controllers can ask for a goal and assume it is there. Without it
 * every handler grows an existence check and an error body, and they drift apart
 * - one answering 404, another 500, a third an empty 200 that looks to a client
 * exactly like a goal with nothing in it.
 */
@RestControllerAdvice
class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler({ GoalService.UnknownGoalException.class,
            GoalService.UnknownAgentException.class })
    ResponseEntity<Map<String, Object>> notFound(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body(ex.getMessage()));
    }

    /** Field-level detail, because "400 Bad Request" alone is a debugging tax. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException ex) {
        Map<String, Object> response = body("request validation failed");
        Map<String, String> fields = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach((error) -> fields.put(error.getField(), error.getDefaultMessage()));
        response.put("fields", fields);
        return ResponseEntity.badRequest().body(response);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, Object>> illegal(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(body(ex.getMessage()));
    }

    /**
     * The catch-all logs the cause and returns a generic message.
     *
     * <p>Deliberately does not echo the exception: a stack trace or SQL fragment
     * on a public demo URL is an information leak, and the detail belongs in the
     * server log where it is actually useful.
     *
     * <p><strong>Framework exceptions are rethrown, not swallowed.</strong>
     * Spring signals "no such route" and "method not allowed" as exceptions that
     * already carry the correct status. Catching them here reported a mistyped
     * URL as a 500, which cost real debugging time: an endpoint that was simply
     * mapped elsewhere looked like a server fault. Anything implementing
     * {@link ErrorResponse} knows its own status better than this method does.
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> unexpected(Exception ex) throws Exception {
        if (ex instanceof ErrorResponse) {
            throw ex;
        }

        // The client hung up mid-response - routine for SSE, where every closed
        // browser tab ends this way. Not a fault, and not worth a stack trace.
        if (ex instanceof AsyncRequestNotUsableException) {
            log.debug("Client disconnected before the response completed");
            throw ex;
        }

        log.error("Unhandled failure serving an API request", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(body("internal error"));
    }

    private static Map<String, Object> body(String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("error", (message != null) ? message : "unknown error");
        return response;
    }
}
