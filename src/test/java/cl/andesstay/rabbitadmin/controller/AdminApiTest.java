package cl.andesstay.rabbitadmin.controller;

import cl.andesstay.rabbitadmin.config.SecurityConfig;
import cl.andesstay.rabbitadmin.service.OperacionNoPermitidaException;
import cl.andesstay.rabbitadmin.service.RabbitAdminService;
import cl.andesstay.rabbitadmin.service.RecursoEnConflictoException;
import cl.andesstay.rabbitadmin.service.RecursoNoEncontradoException;
import cl.andesstay.rabbitadmin.service.modelo.Definiciones.DefinicionBinding;
import cl.andesstay.rabbitadmin.service.modelo.Definiciones.DefinicionCola;
import cl.andesstay.rabbitadmin.service.modelo.Definiciones.EstadoBinding;
import cl.andesstay.rabbitadmin.service.modelo.Definiciones.EstadoCola;
import cl.andesstay.rabbitadmin.service.modelo.TipoDestino;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.net.ConnectException;
import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Capa web completa (controladores, ManejadorDeErrores y SecurityConfig reales) con el servicio
 * simulado: comprueba rutas, verbos, códigos HTTP y cuerpos de error, no RabbitMQ.
 */
@WebMvcTest({ColaController.class, ExchangeController.class, BindingController.class})
@Import(SecurityConfig.class)
class AdminApiTest {

    @Autowired
    private MockMvc mvc;
    @MockBean
    private RabbitAdminService service;
    @MockBean
    private JwtDecoder jwtDecoder;

    private static final EstadoCola COLA = new EstadoCola("q.prueba", true, false, Map.of(), 0, 0);

    private static RequestPostProcessor admin() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_Admin"));
    }

    // ── Seguridad ────────────────────────────────────────────────────────────

    @Test
    void sinTokenResponde401EnJson() throws Exception {
        mvc.perform(get("/api/v1/admin/queues"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("TOKEN_INVALIDO"))
                .andExpect(jsonPath("$.timestamp").exists());
        verifyNoInteractions(service);
    }

    @Test
    void unRolDistintoDeAdminResponde403() throws Exception {
        mvc.perform(delete("/api/v1/admin/queues/q.prueba")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_Operador"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ROL_INSUFICIENTE"));
        verifyNoInteractions(service);
    }

    // ── Colas ────────────────────────────────────────────────────────────────

    @Test
    void consultaUnaColaConPuntosEnElNombre() throws Exception {
        when(service.consultarCola("q.cmd.email")).thenReturn(
                new EstadoCola("q.cmd.email", true, false, Map.of("x-dead-letter-exchange", "cmd.dead.dlx"), 4, 1));

        mvc.perform(get("/api/v1/admin/queues/q.cmd.email").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombre").value("q.cmd.email"))
                .andExpect(jsonPath("$.argumentos['x-dead-letter-exchange']").value("cmd.dead.dlx"))
                .andExpect(jsonPath("$.mensajes").value(4))
                .andExpect(jsonPath("$.consumidores").value(1));
    }

    @Test
    void crearUnaColaResponde201ConLocationYAplicaLosValoresPorOmision() throws Exception {
        when(service.crearCola(any())).thenReturn(COLA);

        mvc.perform(post("/api/v1/admin/queues").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"q.prueba\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/v1/admin/queues/q.prueba"))
                .andExpect(jsonPath("$.nombre").value("q.prueba"));

        ArgumentCaptor<DefinicionCola> definicion = ArgumentCaptor.forClass(DefinicionCola.class);
        verify(service).crearCola(definicion.capture());
        assertThat(definicion.getValue().durable()).isTrue();
        assertThat(definicion.getValue().autoEliminar()).isFalse();
    }

    @Test
    void actualizarTomaElNombreDeLaRuta() throws Exception {
        when(service.actualizarCola(any())).thenReturn(COLA);

        mvc.perform(put("/api/v1/admin/queues/q.prueba").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ttlMensajesMs\":60000}"))
                .andExpect(status().isOk());

        ArgumentCaptor<DefinicionCola> definicion = ArgumentCaptor.forClass(DefinicionCola.class);
        verify(service).actualizarCola(definicion.capture());
        assertThat(definicion.getValue().nombre()).isEqualTo("q.prueba");
        assertThat(definicion.getValue().ttlMensajesMs()).isEqualTo(60000L);
    }

    @Test
    void consultarUnaColaInexistenteResponde404() throws Exception {
        when(service.consultarCola("q.nada")).thenThrow(new RecursoNoEncontradoException("la cola", "q.nada"));

        mvc.perform(get("/api/v1/admin/queues/q.nada").with(admin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("RECURSO_NO_ENCONTRADO"))
                .andExpect(jsonPath("$.mensaje").value("No existe la cola 'q.nada'"));
    }

    @Test
    void eliminarUnaColaConMensajesResponde409YConForzarResponde204() throws Exception {
        doThrow(new RecursoEnConflictoException("La cola 'q.prueba' tiene 5 mensaje(s)"))
                .when(service).eliminarCola("q.prueba", false);

        mvc.perform(delete("/api/v1/admin/queues/q.prueba").with(admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("RECURSO_EN_CONFLICTO"));

        mvc.perform(delete("/api/v1/admin/queues/q.prueba").param("forzar", "true").with(admin()))
                .andExpect(status().isNoContent());
        verify(service).eliminarCola("q.prueba", true);
    }

    @Test
    void sinBrokerResponde503() throws Exception {
        when(service.listarColas()).thenThrow(new AmqpConnectException(new ConnectException("Connection refused")));

        mvc.perform(get("/api/v1/admin/queues").with(admin()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("BROKER_NO_DISPONIBLE"));
    }

    // ── Exchanges ────────────────────────────────────────────────────────────

    @Test
    void unTipoDeExchangeDesconocidoResponde400() throws Exception {
        mvc.perform(post("/api/v1/admin/exchanges").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"ex.prueba\",\"tipo\":\"headers\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("SOLICITUD_INVALIDA"))
                .andExpect(jsonPath("$.campos[0].campo").value("tipo"))
                .andExpect(jsonPath("$.campos[0].mensaje").value("debe ser direct, topic o fanout"));
        verifyNoInteractions(service);
    }

    // ── Validación de entrada (indicador 8) ──────────────────────────────────
    // En todos: 400 SOLICITUD_INVALIDA, un error por campo, y el servicio NO se invoca.

    @Test
    void crearUnaColaSinNombreResponde400() throws Exception {
        mvc.perform(post("/api/v1/admin/queues").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"durable\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("SOLICITUD_INVALIDA"))
                .andExpect(jsonPath("$.mensaje").value("La solicitud tiene un error de validación"))
                .andExpect(jsonPath("$.campos[0].campo").value("nombre"))
                .andExpect(jsonPath("$.campos[0].mensaje").value("es obligatorio"));
        verifyNoInteractions(service);
    }

    @Test
    void crearUnaColaConNombreEnBlancoResponde400() throws Exception {
        mvc.perform(post("/api/v1/admin/queues").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nombre\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.length()").value(1))
                .andExpect(jsonPath("$.campos[0].mensaje").value("es obligatorio"));
        verifyNoInteractions(service);
    }

    @Test
    void unNombreConEspaciosOTildesResponde400() throws Exception {
        mvc.perform(post("/api/v1/admin/queues").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nombre\":\"cola de recepción\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos[0].campo").value("nombre"))
                .andExpect(jsonPath("$.campos[0].mensaje").value(
                        "solo admite letras sin tilde, dígitos y los caracteres . _ - :"));
        verifyNoInteractions(service);
    }

    @Test
    void unNombreDeMasDe255CaracteresResponde400() throws Exception {
        String largo = "q." + "x".repeat(260);
        mvc.perform(post("/api/v1/admin/queues").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nombre\":\"" + largo + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos[0].mensaje").value("admite como máximo 255 caracteres"));
        verifyNoInteractions(service);
    }

    @Test
    void ttlNegativoYLargoCeroSonInvalidosYSeReportanJuntos() throws Exception {
        mvc.perform(post("/api/v1/admin/queues").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"q.demo\",\"ttlMensajesMs\":-1,\"maxMensajes\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value("La solicitud tiene 2 errores de validación"))
                .andExpect(jsonPath("$.campos[?(@.campo=='ttlMensajesMs')].mensaje").value("no puede ser negativo"))
                .andExpect(jsonPath("$.campos[?(@.campo=='maxMensajes')].mensaje").exists());
        verifyNoInteractions(service);
    }

    @Test
    void unTtlMayorAlMaximoDeRabbitMqResponde400() throws Exception {
        mvc.perform(post("/api/v1/admin/queues").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"q.demo\",\"ttlMensajesMs\":4294967296}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos[0].campo").value("ttlMensajesMs"));
        verifyNoInteractions(service);
    }

    @Test
    void unaDeadLetterRoutingKeySinExchangeEsUnaConfiguracionInvalida() throws Exception {
        mvc.perform(post("/api/v1/admin/queues").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"q.demo\",\"deadLetterRoutingKey\":\"demo.dead\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos[0].mensaje").value(org.hamcrest.Matchers.containsString(
                        "deadLetterRoutingKey solo tiene efecto junto con deadLetterExchange")));
        verifyNoInteractions(service);
    }

    @Test
    void unDeadLetterExchangeVacioResponde400() throws Exception {
        mvc.perform(post("/api/v1/admin/queues").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"q.demo\",\"deadLetterExchange\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos[0].campo").value("deadLetterExchange"));
        verifyNoInteractions(service);
    }

    @Test
    void unCampoDesconocidoResponde400EnVezDeIgnorarse() throws Exception {
        // "ttlMensajes" en vez de "ttlMensajesMs": si se ignorara, la cola se crearía sin TTL.
        mvc.perform(post("/api/v1/admin/queues").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"q.demo\",\"ttlMensajes\":60000}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos[0].campo").value("ttlMensajes"))
                .andExpect(jsonPath("$.campos[0].mensaje").value(org.hamcrest.Matchers.containsString("campo desconocido")));
        verifyNoInteractions(service);
    }

    @Test
    void unValorDeTipoIncorrectoResponde400() throws Exception {
        mvc.perform(post("/api/v1/admin/queues").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"q.demo\",\"ttlMensajesMs\":\"un minuto\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos[0].campo").value("ttlMensajesMs"))
                .andExpect(jsonPath("$.campos[0].mensaje").value("tiene un valor de tipo incorrecto"));
        verifyNoInteractions(service);
    }

    @Test
    void unJsonMalFormadoResponde400() throws Exception {
        mvc.perform(post("/api/v1/admin/queues").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nombre\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("SOLICITUD_INVALIDA"));
        verifyNoInteractions(service);
    }

    @Test
    void elPutValidaElNombreDeLaRutaYElCuerpo() throws Exception {
        mvc.perform(put(URI.create("/api/v1/admin/queues/cola%20mala")).with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ttlMensajesMs\":-5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos[?(@.campo=='nombre')]").exists())
                .andExpect(jsonPath("$.campos[?(@.campo=='ttlMensajesMs')]").exists());
        verifyNoInteractions(service);
    }

    @Test
    void forzarConUnValorQueNoEsBooleanoResponde400() throws Exception {
        mvc.perform(delete("/api/v1/admin/queues/q.demo").param("forzar", "quizas").with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos[0].campo").value("forzar"));
        verifyNoInteractions(service);
    }

    @Test
    void crearUnBindingSinDestinoNiExchangeResponde400ConAmbosCampos() throws Exception {
        mvc.perform(post("/api/v1/admin/bindings").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"routingKey\":\"a b\",\"tipoDestino\":\"topic\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos[?(@.campo=='exchange')]").exists())
                .andExpect(jsonPath("$.campos[?(@.campo=='destino')]").exists())
                .andExpect(jsonPath("$.campos[?(@.campo=='routingKey')].mensaje").value("no puede contener espacios"))
                .andExpect(jsonPath("$.campos[?(@.campo=='tipoDestino')].mensaje").value("debe ser COLA o EXCHANGE"));
        verifyNoInteractions(service);
    }

    @Test
    void eliminarUnBindingSinElParametroExchangeResponde400() throws Exception {
        mvc.perform(delete("/api/v1/admin/bindings").param("destino", "q.demo").with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos[0].campo").value("exchange"))
                .andExpect(jsonPath("$.campos[0].mensaje").value("es obligatorio"));
        verifyNoInteractions(service);
    }

    @Test
    void eliminarUnBindingConParametrosInvalidosResponde400() throws Exception {
        mvc.perform(delete(URI.create("/api/v1/admin/bindings?exchange=ex%20malo&destino=q.demo&routingKey=a%20b"))
                        .with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos[?(@.campo=='exchange')]").exists())
                .andExpect(jsonPath("$.campos[?(@.campo=='routingKey')]").exists());
        verifyNoInteractions(service);
    }

    @Test
    void unRecursoProtegidoResponde403ConElPatronEnElMensaje() throws Exception {
        doThrow(new OperacionNoPermitidaException(
                "La cola 'q.cmd.email' coincide con el patrón protegido 'q.cmd.*' (andesstay.rabbitadmin.protegidos.colas)"))
                .when(service).eliminarCola("q.cmd.email", true);

        mvc.perform(delete("/api/v1/admin/queues/q.cmd.email").param("forzar", "true").with(admin()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("OPERACION_NO_PERMITIDA"))
                .andExpect(jsonPath("$.mensaje").value(org.hamcrest.Matchers.containsString("q.cmd.*")));
    }

    @Test
    void eliminarUnExchangeReservadoResponde403() throws Exception {
        doThrow(new OperacionNoPermitidaException("'amq.topic' es un nombre reservado"))
                .when(service).eliminarExchange("amq.topic", false);

        mvc.perform(delete("/api/v1/admin/exchanges/amq.topic").with(admin()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("OPERACION_NO_PERMITIDA"));
    }

    // ── Bindings ─────────────────────────────────────────────────────────────

    @Test
    void crearUnBindingResponde201() throws Exception {
        when(service.crearBinding(any())).thenReturn(
                new EstadoBinding("cmd.topic", "q.prueba", TipoDestino.COLA, "prueba.#"));

        mvc.perform(post("/api/v1/admin/bindings").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"exchange\":\"cmd.topic\",\"destino\":\"q.prueba\",\"routingKey\":\"prueba.#\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tipoDestino").value("COLA"))
                .andExpect(jsonPath("$.routingKey").value("prueba.#"));
    }

    @Test
    void eliminarUnBindingRecibeLaRoutingKeyCodificada() throws Exception {
        // URI y no String: con un String, MockMvc vuelve a codificar el '%' y enviaría %2523, cosa
        // que ningún cliente real hace. Con la URI ya codificada llega igual que desde curl.
        mvc.perform(delete(URI.create("/api/v1/admin/bindings?exchange=cmd.topic&destino=q.prueba&tipoDestino=cola&routingKey=prueba.%23"))
                        .with(admin()))
                .andExpect(status().isNoContent());

        verify(service).eliminarBinding(
                new DefinicionBinding("cmd.topic", "q.prueba", TipoDestino.COLA, "prueba.#"));
    }

    @Test
    void listaLosBindings() throws Exception {
        when(service.listarBindings()).thenReturn(List.of(
                new EstadoBinding("cmd.direct", "q.cmd.email", TipoDestino.COLA, "email.send")));

        mvc.perform(get("/api/v1/admin/bindings").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].exchange").value("cmd.direct"))
                .andExpect(jsonPath("$[0].routingKey").value("email.send"));
    }
}
