package com.safeheal.agent;

public class ClusterReadException extends RuntimeException {
    public ClusterReadException(String message) {
        super(message);
    }

    public ClusterReadException(String message, Throwable cause) {
        super(message, cause);
    }
}
