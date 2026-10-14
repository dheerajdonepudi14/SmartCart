package com.smartcart.auth;

import com.smartcart.auth.AuthDtos.*;
import com.smartcart.config.AppProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Auth")
public class AuthController {
    static final String REFRESH_COOKIE = "sc_refresh";

    private final AuthService auth;
    private final boolean cookieSecure;

    public AuthController(AuthService auth, AppProperties props) {
        this.auth = auth;
        this.cookieSecure = props.jwt().cookieSecure();
    }

    @PostMapping("/register")
    @Operation(summary = "Create an account and sign in")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest req) {
        return withCookie(auth.register(req));
    }

    @PostMapping("/login")
    @Operation(summary = "Sign in; returns an access token and sets an HttpOnly refresh cookie")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest req) {
        return withCookie(auth.login(req));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Exchange the refresh cookie for a new access token (rotates the cookie)")
    public ResponseEntity<AuthResponse> refresh(@CookieValue(name = REFRESH_COOKIE, required = false) String token) {
        return withCookie(auth.refresh(token));
    }

    @PostMapping("/logout")
    @Operation(summary = "Revoke the refresh token and clear the cookie")
    public ResponseEntity<Void> logout(@CookieValue(name = REFRESH_COOKIE, required = false) String token) {
        auth.logout(token);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString())
                .build();
    }

    @GetMapping("/me")
    @Operation(summary = "Current user profile")
    public UserDto me(@AuthenticationPrincipal AuthUser user) {
        return auth.me(user.id());
    }

    private ResponseEntity<AuthResponse> withCookie(AuthResult result) {
        ResponseCookie c = cookie(result.refreshToken(), Duration.ofSeconds(auth.refreshTtlSeconds()));
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, c.toString()).body(result.response());
    }

    private ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Lax")
                .path("/api/auth")
                .maxAge(maxAge)
                .build();
    }
}
