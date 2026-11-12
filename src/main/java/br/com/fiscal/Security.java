package br.com.fiscal;

import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;

@Configuration
class Security {
    record Account(String username, String password, UUID tenant, UUID issuer, String role) implements UserDetails {
        public String getUsername() { return username; }
        public String getPassword() { return password; }
        public java.util.Collection<? extends org.springframework.security.core.GrantedAuthority> getAuthorities() {
            return java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + role));
        }
    }
    @Bean UserDetailsService users(Environment env) {
        var password = env.getRequiredProperty("APP_ADMIN_PASSWORD");
        if (password.length() < 12) throw new IllegalArgumentException("APP_ADMIN_PASSWORD exige 12 caracteres ou mais");
        var encoder = new BCryptPasswordEncoder();
        if (org.springframework.util.StringUtils.hasText(env.getProperty("APP_CLIENT_PASSWORD")) && env.getRequiredProperty("APP_CLIENT_PASSWORD").length() < 12)
            throw new IllegalArgumentException("APP_CLIENT_PASSWORD exige 12 caracteres ou mais");
        var accounts = new java.util.HashMap<String, Account>();
        accounts.put("contador", new Account("contador", encoder.encode(password),
            UUID.fromString(env.getProperty("APP_TENANT_ID", "00000000-0000-0000-0000-000000000001")), null, "GESTOR"));
        if (org.springframework.util.StringUtils.hasText(env.getProperty("APP_CLIENT_PASSWORD"))) accounts.put("cliente", new Account("cliente",
            encoder.encode(env.getRequiredProperty("APP_CLIENT_PASSWORD")), accounts.get("contador").tenant(),
            UUID.fromString(env.getRequiredProperty("APP_CLIENT_ISSUER_ID")), "CLIENTE"));
        return username -> { var account = accounts.get(username); if (account == null) throw new UsernameNotFoundException("Usuário inválido"); return account; };
    }
    @Bean BCryptPasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
    @Bean java.time.Clock clock() { return java.time.Clock.systemUTC(); }
    @Bean AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
    @Bean SecurityFilterChain chain(HttpSecurity http, LoginSessions sessions) throws Exception {
        return http.csrf(csrf -> csrf.disable()) // Cookie-session CSRF is checked by SessionAuthenticationFilter.
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a.requestMatchers("/api/auth/login","/health").permitAll().anyRequest().authenticated())
            .addFilterBefore(new SessionAuthenticationFilter(sessions), BasicAuthenticationFilter.class)
            .httpBasic(Customizer.withDefaults()).build();
    }
}
