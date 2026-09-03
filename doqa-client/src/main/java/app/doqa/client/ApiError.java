package app.doqa.client;

/**
 * Raised for non-retryable HTTP errors or exhausted retries. {@link #status()} carries the HTTP
 * status the server answered with, or {@code 0} when no response arrived at all (connection
 * failure, timeout, open circuit) - callers branch on it to explain a failure and to decide what
 * a retry could still achieve.
 */
public class ApiError extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;

    public ApiError(String message) {
        this(message, 0);
    }

    public ApiError(String message, int status) {
        super(message);
        this.status = status;
    }

    public ApiError(String message, Throwable cause) {
        super(message, cause);
        this.status = 0;
    }

    /** HTTP status of the rejected request; {@code 0} when the server never answered. */
    public int status() {
        return status;
    }

    /** 401/403: the credentials are unusable, and every further request with them will fail too. */
    public boolean isAuthFailure() {
        return status == 401 || status == 403;
    }
}
