package com.smartcart.auth;

/** Published inside the registration transaction; listeners act after commit. */
public record UserRegisteredEvent(Long userId) {}
