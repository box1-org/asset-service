package org.box1.asset.api;

/** A query parameter or cursor is invalid. The message is safe to return. */
public class InvalidRequestException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String code;

    public InvalidRequestException(String code, String message) {
        super(message, null, false, false);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
