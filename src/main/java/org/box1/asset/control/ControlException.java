package org.box1.asset.control;

/** A refused control action. {@code code} is stable; the message is safe to show to the operator. */
public class ControlException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final int status;
    private final String code;

    public ControlException(int status, String code, String message) {
        super(message, null, false, false);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
