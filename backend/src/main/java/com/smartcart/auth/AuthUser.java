package com.smartcart.auth;

/** Authenticated principal extracted from a verified access token. */
public record AuthUser(Long id, String email, String role) {
    public boolean isAdmin() {
        return "ROLE_ADMIN".equals(role);
    }
}
