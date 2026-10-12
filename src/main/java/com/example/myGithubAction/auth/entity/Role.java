package com.example.myGithubAction.auth.entity;

public enum Role {
    USER,
    ADMIN;

    /** Lenient parse: null, blank or unknown values mean USER (never ADMIN by accident). */
    public static Role fromClaim(Object value) {
        if (value == null) {
            return USER;
        }
        try {
            return valueOf(value.toString().trim());
        } catch (IllegalArgumentException e) {
            return USER;
        }
    }
}
