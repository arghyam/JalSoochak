package org.arghyam.jalsoochak.tenant.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {

    private static final String[] SWAGGER_PATHS = {"/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html"};

    private final JwtAuthConverter jwtAuthConverter;
    private final Environment environment;
    private final SecurityExceptionHandler securityExceptionHandler;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        boolean isProd = environment.acceptsProfiles(Profiles.of("prod"));

        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers("/error", "/actuator/health/**", "/actuator/info").permitAll();
                    auth.requestMatchers(HttpMethod.GET,
                            "/api/v1/tenants",
                            "/api/v1/tenants/*/config/public",
                            "/api/v1/tenants/*/logo",
                            "/api/v1/tenants/*/location-hierarchy/*",
                            "/api/v1/tenants/*/locations/*").permitAll();
                    if (isProd) {
                        auth.requestMatchers(SWAGGER_PATHS).authenticated();
                    } else {
                        auth.requestMatchers(SWAGGER_PATHS).permitAll();
                    }
                    auth.anyRequest().authenticated();
                })
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(securityExceptionHandler)
                        .accessDeniedHandler(securityExceptionHandler))
                .oauth2ResourceServer(oauth -> oauth
                        .authenticationEntryPoint(securityExceptionHandler)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthConverter)));

        return http.build();
    }
}
