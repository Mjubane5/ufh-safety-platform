package za.ac.ufh.safety.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException ex) {
        return ResponseEntity.status(ex.getStatus())
            .body(new ErrorResponse(ex.getCode(), ex.getMessage(), ex.getField()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        var fieldError = ex.getBindingResult().getFieldErrors().stream().findFirst().orElse(null);
        String field = fieldError == null ? null : fieldError.getField();
        String message = fieldError == null ? "Validation failed." : fieldError.getDefaultMessage();
        return ResponseEntity.badRequest()
            .body(new ErrorResponse("VALIDATION_FAILED", message, field));
    }

    // AuthService checks for a duplicate email before inserting, but two
    // registrations arriving together can both pass that check and leave the
    // second one to hit the unique constraint. Without this the student sees a
    // 500 where the contract promises a 400.
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDuplicate(DataIntegrityViolationException ex) {
        log.warn("Database constraint rejected a write", ex);
        return ResponseEntity.badRequest()
            .body(new ErrorResponse(
                "VALIDATION_FAILED",
                "That email or student number is already registered.",
                null));
    }

    // A URL with no handler is the client asking for something that does not
    // exist, so it is a 404. Without this it fell through to the catch-all
    // below and came back as 500, which tells the frontend team the backend
    // crashed when in fact they had the wrong path or the endpoint is not
    // built yet.
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(new ErrorResponse("NOT_FOUND", "That endpoint does not exist.", null));
    }

    // A required @RequestParam that was never sent (GET /api/patrols/recent
    // with no latitude/longitude, for example) is client error, not ours, but
    // Spring throws before the controller body runs, so a service's own
    // "field is required" check never gets the chance to fire. Same class of
    // bug as NoResourceFoundException above: without this it fell through to
    // the catch-all and came back as a 500 for what is really a 400.
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParameter(MissingServletRequestParameterException ex) {
        return ResponseEntity.badRequest()
            .body(new ErrorResponse(
                "VALIDATION_FAILED",
                ex.getParameterName() + " is required.",
                ex.getParameterName()));
    }

    // A body Jackson cannot read is bad client data, not a server fault: a
    // malformed JSON document, or a value outside an enum such as a status of
    // "en-route". The contract calls that 400.
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest()
            .body(new ErrorResponse("VALIDATION_FAILED", "The request body could not be read.", null));
    }

    // The stack trace is logged and not returned: it can name internal classes
    // and table columns, which is not something to hand to the caller.
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.internalServerError()
            .body(new ErrorResponse("SERVER_ERROR", "An unexpected server error occurred.", null));
    }
}
