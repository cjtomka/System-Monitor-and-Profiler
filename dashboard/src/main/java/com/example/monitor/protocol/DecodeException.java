package com.example.monitor.protocol;

/** A line that cannot be turned into a Message. The connection can carry on. */
public class DecodeException extends Exception {
    public DecodeException(String message, Throwable cause) {
        super(message, cause);
    }
}
