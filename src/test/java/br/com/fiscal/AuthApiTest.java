package br.com.fiscal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.time.*;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockCookie;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"APP_ADMIN_PASSWORD=session-test-pass-123", "spring.datasource.url=jdbc:h2:mem:sessions;DB_CLOSE_DELAY=-1"},
    classes={Application.class, AuthApiTest.TimeConfiguration.class})
@AutoConfigureMockMvc
class AuthApiTest extends DatabaseTestSupport {
    static final Instant LOGIN_TIME = Instant.parse("2026-11-11T12:00:00Z");
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired MutableClock clock;
    record Login(Cookie cookie, JsonNode body, String header) {}

    static class MutableClock extends Clock {
        private Instant instant = LOGIN_TIME;
        void set(Instant value) { instant = value; }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return Clock.fixed(instant, zone); }
        public Instant instant() { return instant; }
    }
    @TestConfiguration static class TimeConfiguration {
        @Bean @Primary MutableClock testClock() { return new MutableClock(); }
    }
    @BeforeEach void resetTime() { clock.set(LOGIN_TIME); }

    Login login(Cookie previous) throws Exception {
        var request = post("/api/auth/login").header("X-Requested-With", "XMLHttpRequest")
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("username", "contador", "password", "session-test-pass-123")));
        if (previous != null) request.cookie(previous);
        var response = mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse();
        String header = response.getHeader("Set-Cookie");
        assertNotNull(header);
        return new Login(MockCookie.parse(header), json.readTree(response.getContentAsString()), header);
    }

    @Test void cookieRestoresSessionAndExpiresExactlyEightHoursAfterLoginWithoutSliding() throws Exception {
        var login = login(null);
        assertTrue(login.header().contains("HttpOnly"));
        assertTrue(login.header().contains("SameSite=Strict"));
        assertEquals(28800, login.cookie().getMaxAge());
        assertEquals("/api", login.cookie().getPath());
        assertEquals(LOGIN_TIME.plus(Duration.ofHours(8)).toString(), login.body().get("expiresAt").asText());
        assertFalse(login.body().toString().contains("session-test-pass-123"));
        assertFalse(login.body().toString().contains(login.cookie().getValue()));
        mvc.perform(get("/api/me").cookie(login.cookie())).andExpect(status().isOk()).andExpect(jsonPath("$.username").value("contador"));
        clock.set(LOGIN_TIME.plus(Duration.ofHours(8)).minusSeconds(1));
        mvc.perform(get("/api/auth/session").cookie(login.cookie())).andExpect(status().isOk())
            .andExpect(jsonPath("$.expiresAt").value(login.body().get("expiresAt").asText()))
            .andExpect(header().string("Cache-Control", "no-store"));
        clock.set(LOGIN_TIME.plus(Duration.ofHours(8)));
        mvc.perform(get("/api/auth/session").cookie(login.cookie())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me").cookie(login.cookie())).andExpect(status().isUnauthorized());
    }

    @Test void cookieAuthenticatedWritesRequireCsrfTokenBoundToTheSession() throws Exception {
        var first = login(null);
        var second = login(null);
        String body = "{\"cnpj\":\"12345678000195\",\"name\":\"Sessão teste\",\"municipality\":\"3550308\"}";
        mvc.perform(post("/api/issuers").cookie(first.cookie()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/issuers").cookie(first.cookie()).header("X-CSRF-Token",second.body().get("csrfToken").asText())
            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/issuers").cookie(first.cookie()).header("X-CSRF-Token",first.body().get("csrfToken").asText())
            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
    }

    @Test void logoutRevokesTheServerSessionAndClearsCookieWithoutRevokingOtherLogins() throws Exception {
        var first = login(null);
        var second = login(null);
        mvc.perform(post("/api/auth/logout").cookie(first.cookie())).andExpect(status().isForbidden());
        var response = mvc.perform(post("/api/auth/logout").cookie(first.cookie()).header("X-CSRF-Token",first.body().get("csrfToken").asText()))
            .andExpect(status().isNoContent()).andReturn().getResponse();
        assertTrue(response.getHeader("Set-Cookie").contains("Max-Age=0"));
        mvc.perform(get("/api/me").cookie(first.cookie())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me").cookie(second.cookie())).andExpect(status().isOk());
    }

    @Test void loginRotatesExistingSessionAndRejectsInvalidCredentialsOrForgedCookie() throws Exception {
        var first = login(null);
        var second = login(first.cookie());
        assertNotEquals(first.cookie().getValue(), second.cookie().getValue());
        mvc.perform(get("/api/me").cookie(first.cookie())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me").cookie(second.cookie())).andExpect(status().isOk());
        mvc.perform(get("/api/me").cookie(new Cookie(LoginSessions.COOKIE_NAME,"a".repeat(43)))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").header("X-Requested-With","XMLHttpRequest").contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"contador\",\"password\":\"incorreta\"}")).andExpect(status().isUnauthorized())
            .andExpect(header().doesNotExist("Set-Cookie"));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"contador\",\"password\":\"session-test-pass-123\"}")).andExpect(status().isForbidden());
    }
}
