package org.opendamn.dbpf;

/** Thrown when a DBPF file or one of its resources is malformed. */
public class DbpfException extends RuntimeException {
    public DbpfException(String message) {
        super(message);
    }

    public DbpfException(String message, Throwable cause) {
        super(message, cause);
    }
}
