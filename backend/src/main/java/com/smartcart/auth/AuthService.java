package com.smartcart.auth;

import com.smartcart.auth.AuthDtos.*;
import com.smartcart.common.ApiException;
import com.smartcart.common.Crypto;
import com.smartcart.config.AppProperties;
import com.smartcart.user.Role;
import com.smartcart.user.User;
import com.smartcart.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

@Service
public class AuthService {
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final ApplicationEventPublisher events;
    private final long refreshDays;

    public AuthService(UserRepository users, RefreshTokenRepository refreshTokens, PasswordEncoder encoder,
                       JwtService jwt, ApplicationEventPublisher events, AppProperties props) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.encoder = encoder;
        this.jwt = jwt;
        this.events = events;
        this.refreshDays = props.jwt().refreshTokenDays();
    }

    public long refreshTtlSeconds() {
        return refreshDays * 24 * 3600;
    }

    @Transactional
    public AuthResult register(RegisterRequest req) {
        String email = req.email().trim().toLowerCase(Locale.ROOT);
        if (users.existsByEmailIgnoreCase(email)) {
            throw ApiException.conflict("An account with this email already exists");
        }
        User user = users.save(new User(req.name().trim(), email, encoder.encode(req.password()), Role.ROLE_USER));
        log.info("User registered userId={}", user.getId());
        events.publishEvent(new UserRegisteredEvent(user.getId()));
        return issue(user);
    }

    @Transactional
    public AuthResult login(LoginRequest req) {
        User user = users.findByEmailIgnoreCase(req.email().trim())
                .filter(u -> encoder.matches(req.password(), u.getPasswordHash()))
                .orElseThrow(() -> new BadCredentialsException("bad credentials"));
        log.info("User logged in userId={}", user.getId());
        return issue(user);
    }

    /** Rotates the refresh token: the presented token is revoked and a new pair is issued. */
    @Transactional(noRollbackFor = ApiException.class)
    public AuthResult refresh(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw ApiException.unauthorized("Missing refresh token");
        }
        RefreshToken stored = refreshTokens.findByTokenHash(Crypto.sha256Hex(rawRefreshToken))
                .orElseThrow(() -> ApiException.unauthorized("Invalid refresh token"));
        if (stored.isRevoked()) {
            // Reuse of a rotated token signals theft: revoke the whole family for this user.
            refreshTokens.revokeAllForUser(stored.getUser().getId());
            log.warn("Refresh token reuse detected userId={}", stored.getUser().getId());
            throw ApiException.unauthorized("Refresh token revoked");
        }
        if (!stored.isActive()) {
            throw ApiException.unauthorized("Refresh token expired");
        }
        stored.revoke();
        return issue(stored.getUser());
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return;
        }
        refreshTokens.findByTokenHash(Crypto.sha256Hex(rawRefreshToken)).ifPresent(RefreshToken::revoke);
    }

    @Transactional(readOnly = true)
    public UserDto me(Long userId) {
        return users.findById(userId).map(UserDto::of).orElseThrow(() -> ApiException.notFound("User"));
    }

    private AuthResult issue(User user) {
        String raw = Crypto.randomToken(32);
        refreshTokens.save(new RefreshToken(user, Crypto.sha256Hex(raw),
                Instant.now().plus(refreshDays, ChronoUnit.DAYS)));
        AuthResponse body = new AuthResponse(jwt.issueAccessToken(user), "Bearer",
                jwt.accessTokenTtlSeconds(), UserDto.of(user));
        return new AuthResult(body, raw);
    }

    @Scheduled(cron = "0 17 3 * * *")
    @Transactional
    public void purgeExpiredTokens() {
        int n = refreshTokens.deleteExpired(Instant.now().minus(1, ChronoUnit.DAYS));
        if (n > 0) {
            log.info("Purged {} expired/revoked refresh tokens", n);
        }
    }
}
