package br.com.fiscal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

class SessionAuthenticationFilter extends OncePerRequestFilter {
    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private final LoginSessions sessions;

    SessionAuthenticationFilter(LoginSessions sessions) { this.sessions = sessions; }

    static String cookie(HttpServletRequest request) {
        if (request.getCookies() != null) {
            for (var cookie : request.getCookies()) {
                if (LoginSessions.COOKIE_NAME.equals(cookie.getName())) return cookie.getValue();
            }
        }
        return null;
    }

    private void reject(HttpServletResponse response, int status, String error) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("{\"error\":\"" + error + "\"}");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        boolean login = request.getRequestURI().substring(request.getContextPath().length()).equals("/api/auth/login");
        if (login) {
            // A custom header and JSON prevent a cross-origin HTML form from logging the victim in.
            if (!"XMLHttpRequest".equals(request.getHeader("X-Requested-With"))) {
                reject(response, 403, "Cabeçalho de proteção ausente"); return;
            }
            chain.doFilter(request, response); return;
        }
        // Preserve explicit Basic authentication for local API clients. The React app uses only cookies.
        if (request.getHeader("Authorization") != null) { chain.doFilter(request, response); return; }
        String token = cookie(request);
        if (token != null) {
            var session = sessions.find(token);
            if (session.isEmpty()) { reject(response, 401, "Sessão encerrada. Entre novamente."); return; }
            var current = session.get();
            if (!READ_METHODS.contains(request.getMethod()) && !LoginSessions.matches(current.csrfToken(), request.getHeader("X-CSRF-Token"))) {
                reject(response, 403, "Proteção da sessão inválida. Atualize a página."); return;
            }
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(current.account(), null, current.account().getAuthorities()));
            SecurityContextHolder.setContext(context);
            request.setAttribute(LoginSessions.REQUEST_ATTRIBUTE, current);
        }
        chain.doFilter(request, response);
    }
}
