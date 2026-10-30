package com.smartlib.security;

import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.auth0.jwt.interfaces.JWTVerifier;

/** Signs and verifies the HS256 session tokens ({ userId }, valid for JWT_EXPIRES_IN, default 7 days). */
@Service
public class JwtService {

    private static final Pattern DURATION = Pattern.compile("^(\\d+)\\s*([smhdwy]?)$");

    private final Algorithm algorithm;
    private final JWTVerifier verifier;
    private final long lifetimeSeconds;

    public JwtService(@Value("${JWT_SECRET:}") String secret, @Value("${JWT_EXPIRES_IN:7d}") String expiresIn) {
        if (secret.isBlank()) {
            throw new IllegalStateException("JWT_SECRET is not set. Set it in the environment (or .env) before starting the server.");
        }
        this.algorithm = Algorithm.HMAC256(secret);
        this.verifier = JWT.require(algorithm).build();
        this.lifetimeSeconds = parseDuration(expiresIn);
    }

    public String sign(int userId) {
        Instant now = Instant.now();
        return JWT.create()
            .withClaim("userId", userId)
            .withIssuedAt(now)
            .withExpiresAt(now.plusSeconds(lifetimeSeconds))
            .sign(algorithm);
    }

    /** The userId in a valid token, or null when the token is invalid or expired. */
    public Integer verify(String token) {
        try {
            DecodedJWT jwt = verifier.verify(token);
            return jwt.getClaim("userId").asInt();
        } catch (JWTVerificationException e) {
            return null;
        }
    }

    /** "7d", "12h", "30m", "3600" (seconds) → seconds. */
    static long parseDuration(String s) {
        Matcher m = DURATION.matcher(s.trim().toLowerCase());
        if (!m.matches()) return 7 * 86_400L;
        long n = Long.parseLong(m.group(1));
        return switch (m.group(2)) {
            case "m" -> n * 60;
            case "h" -> n * 3_600;
            case "d" -> n * 86_400;
            case "w" -> n * 604_800;
            case "y" -> n * 31_557_600;
            default -> n;
        };
    }
}
