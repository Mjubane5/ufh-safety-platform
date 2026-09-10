package za.ac.ufh.safety.common;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import org.springframework.http.HttpMethod;

import static org.junit.jupiter.api.Assertions.*;

/**
 * These two cases used to fall through to the catch-all and come back as 500.
 * A 500 tells the frontend team the backend crashed, so they go looking for a
 * bug that is not there when the real answer is "wrong URL" or "bad body".
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void unknownEndpointIsNotFoundRatherThanServerError() {
        var response = handler.handleNotFound(
                new NoResourceFoundException(HttpMethod.GET, "api/incidents"));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("NOT_FOUND", response.getBody().error());
        assertNull(response.getBody().field(), "field is null for non-validation errors");
    }

    @Test
    void unreadableBodyIsValidationFailureRatherThanServerError() {
        var response = handler.handleUnreadableBody(
                new HttpMessageNotReadableException("Unknown incident status: en-route"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("VALIDATION_FAILED", response.getBody().error());
    }

    @Test
    void unexpectedErrorDoesNotLeakInternals() {
        var response = handler.handleUnexpected(
                new IllegalStateException("users.password_hash column exploded"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("SERVER_ERROR", response.getBody().error());
        assertFalse(response.getBody().message().contains("password_hash"),
                "the caller must not be told about internal columns");
    }
}
