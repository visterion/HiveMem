package com.hivemem.queen;

/** Thrown when Vistierie's runs API is unreachable, times out, or returns a server error. */
public class VistierieUnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public VistierieUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
