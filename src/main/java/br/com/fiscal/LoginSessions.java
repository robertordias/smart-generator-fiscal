package br.com.fiscal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
class LoginSessions {
    static final Duration LIFETIME = Duration.ofHours(8);
    static final String COOKIE_NAME = "FISCAL_SESSION";
    static final String REQUEST_ATTRIBUTE = LoginSessions.class.getName();
    record Session(Security.Account account, String csrfToken, Instant expiresAt) {}
    record Issued(String token, Session session) {}

    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;

    LoginSessions(Clock clock) { this.clock = clock; }

    Issued create(Security.Account account) {
        Instant now = clock.instant();
        sessions.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
        String token = randomToken();
        Session session = new Session(account, randomToken(), now.plus(LIFETIME));
        sessions.put(hash(token), session);
        return new Issued(token, session);
    }

    Optional<Session> find(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) return Optional.empty();
        String key = hash(token);
        Session session = sessions.get(key);
        if (session == null) return Optional.empty();
        if (!session.expiresAt().isAfter(clock.instant())) {
            sessions.remove(key, session);
            return Optional.empty();
        }
        return Optional.of(session); // Access never extends the deadline set at login.
    }

    void revoke(String token) {
        if (token != null) sessions.remove(hash(token));
    }

    static boolean matches(String expected, String actual) {
        return actual != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
