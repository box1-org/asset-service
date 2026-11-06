package org.box1.asset.consume;

/** A record that can never be processed. The message is a reason code only, because it becomes a DLQ header. */
public class PoisonEventException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public PoisonEventException(String reasonCode) {
        super(reasonCode, null, false, false);
    }
}
