package br.com.fiscal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
class AuthApi {
    record Credentials(@NotBlank @Size(max=100) String username, @NotBlank @Size(max=1024) String password) {
        @Override public String toString() { return "Credentials[username=" + username + ", password=[PROTECTED]]"; }
    }
    record User(String username, java.util.UUID tenantId, String role, String mode) {}
    record SessionResponse(User user, Instant expiresAt, String csrfToken) {
        @Override public String toString() { return "SessionResponse[user=" + user + ", expiresAt=" + expiresAt + ", csrfToken=[PROTECTED]]"; }
    }
    private final AuthenticationManager authentication;
    private final LoginSessions sessions;
    private final boolean secureCookie;

    AuthApi(AuthenticationManager authentication, LoginSessions sessions, Environment env) {
        this.authentication = authentication;
        this.sessions = sessions;
        this.secureCookie = env.getProperty("APP_COOKIE_SECURE", Boolean.class, false);
    }

    private SessionResponse describe(LoginSessions.Session session) {
        var account = session.account();
        return new SessionResponse(new User(account.username(), account.tenant(), account.role(), "NFSE_NATIONAL"), session.expiresAt(), session.csrfToken());
    }

    private void cookie(HttpServletResponse response, String token, long maxAge) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(LoginSessions.COOKIE_NAME, token)
            .httpOnly(true).secure(secureCookie).sameSite("Strict").path("/api").maxAge(maxAge).build().toString());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    @PostMapping(value="/login", consumes="application/json")
    SessionResponse login(@Valid @RequestBody Credentials credentials, HttpServletRequest request, HttpServletResponse response) {
        var result = authentication.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(credentials.username().trim(), credentials.password()));
        sessions.revoke(SessionAuthenticationFilter.cookie(request));
        var issued = sessions.create((Security.Account) result.getPrincipal());
        cookie(response, issued.token(), LoginSessions.LIFETIME.toSeconds());
        return describe(issued.session());
    }

    @GetMapping("/session")
    SessionResponse session(HttpServletRequest request, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        var session = (LoginSessions.Session) request.getAttribute(LoginSessions.REQUEST_ATTRIBUTE);
        if (session == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return describe(session);
    }

    @PostMapping("/logout") @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(HttpServletRequest request, HttpServletResponse response) {
        sessions.revoke(SessionAuthenticationFilter.cookie(request));
        cookie(response, "", 0);
    }

    @ExceptionHandler(AuthenticationException.class) @ResponseStatus(HttpStatus.UNAUTHORIZED)
    Map<String,String> invalidCredentials() { return Map.of("error", "Usuário ou senha incorretos."); }
}
