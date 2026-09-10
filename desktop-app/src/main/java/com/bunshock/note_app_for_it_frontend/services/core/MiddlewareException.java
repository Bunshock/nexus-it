package com.bunshock.note_app_for_it_frontend.services.core;

/**
 * Every failure surfaced by {@link MiddlewareClient} — an HTTP error carrying the middleware's
 * {@code {error:{code,message}}} envelope, or a transport failure (no response at all).
 *
 * <p>{@link #getStatus()} is the HTTP status, or {@code 0} when the request never completed
 * (connection refused / timeout / DNS). {@link #getCode()} is the envelope {@code error.code}
 * (e.g. {@code IDP_NOT_CONFIGURED}, {@code USER_NOT_REGISTERED}), {@code "TRANSPORT"} for a
 * connection failure, or {@code "HTTP_<n>"} when the body wasn't a parseable envelope.
 */
public class MiddlewareException extends RuntimeException {

    private final int status;
    private final String code;

    public MiddlewareException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public MiddlewareException(int status, String code, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
    }

    public int getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    /** No response reached us — connection refused, timeout, unresolved host. */
    public boolean isTransport() {
        return status == 0;
    }

    public boolean isUnauthorized() {
        return status == 401;
    }

    public boolean isCode(String expected) {
        return expected.equals(code);
    }
}
