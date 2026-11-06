package org.box1.asset.store;

/** A database failure. The message never carries row values (logServerErrorDetail=false). */
public class StoreException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public StoreException(String operation, Throwable cause) {
        super(operation + " failed", cause);
    }
}
