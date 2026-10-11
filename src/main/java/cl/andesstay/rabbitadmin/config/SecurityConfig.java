package cl.andesstay.rabbitadmin.config;

import cl.andesstay.rabbitadmin.web.CuerpoError;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.io.IOException;

/**
 * Toda la API del administrador exige un JWT válido de Azure AD con el App Role Admin.
 *
 * Crear o eliminar colas afecta a todos los microservicios, así que no hay rutas abiertas salvo el
 * health del contenedor. La validación de firma, vigencia, issuer y audience la hace el resource
 * server con las propiedades de spring.security.oauth2.resourceserver.jwt.
 *
 * Los 401 y 403 se responden con el mismo cuerpo JSON que el resto de los errores (CuerpoError):
 * sin esto Spring Security devuelve un cuerpo vacío y el cliente no puede distinguir un token
 * vencido de un rol insuficiente.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    public static final String ROL_ADMIN = "Admin";

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
        http.csrf(c -> c.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers("/api/v1/admin/**").hasRole(ROL_ADMIN)
                .anyRequest().denyAll())
            .oauth2ResourceServer(o -> o
                .jwt(j -> j.jwtAuthenticationConverter(jwtConverter()))
                .authenticationEntryPoint((req, res, e) -> escribir(res, objectMapper,
                        HttpServletResponse.SC_UNAUTHORIZED, "TOKEN_INVALIDO",
                        "Falta el token, o es inválido, está vencido o es de un emisor desconocido"))
                .accessDeniedHandler((req, res, e) -> escribir(res, objectMapper,
                        HttpServletResponse.SC_FORBIDDEN, "ROL_INSUFICIENTE",
                        "La administración de RabbitMQ requiere el rol " + ROL_ADMIN)));
        return http.build();
    }

    /** El claim "roles" de Azure AD trae los App Roles; se mapean a ROLE_* para hasRole. */
    @Bean
    public JwtAuthenticationConverter jwtConverter() {
        JwtGrantedAuthoritiesConverter gc = new JwtGrantedAuthoritiesConverter();
        gc.setAuthoritiesClaimName("roles");
        gc.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter c = new JwtAuthenticationConverter();
        c.setJwtGrantedAuthoritiesConverter(gc);
        return c;
    }

    private static void escribir(HttpServletResponse res, ObjectMapper objectMapper,
                                 int status, String codigo, String mensaje) throws IOException {
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(res.getOutputStream(), CuerpoError.de(codigo, mensaje));
    }
}
