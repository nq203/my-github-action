package com.example.myGithubAction.monitoring;

/** A user already has the maximum number of open execution streams (mapped to 429). */
public class TooManyStreamsException extends RuntimeException {
    public TooManyStreamsException(int max) {
        super("Too many open execution streams (max " + max + ")");
    }
}
