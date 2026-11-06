package org.box1.asset.domain;

/** A reading failed validation or admission. The message is safe to return to the client. */
public class RejectedReadingException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final RejectReason reason;

    public RejectedReadingException(RejectReason reason, String message) {
        super(message, null, false, false);
        this.reason = reason;
    }

    public RejectReason reason() {
        return reason;
    }
}
