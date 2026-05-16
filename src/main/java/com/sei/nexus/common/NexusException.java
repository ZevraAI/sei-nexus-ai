package com.sei.nexus.common;

import org.springframework.http.HttpStatus;

public class NexusException extends RuntimeException {

    private final HttpStatus status;

    public NexusException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
