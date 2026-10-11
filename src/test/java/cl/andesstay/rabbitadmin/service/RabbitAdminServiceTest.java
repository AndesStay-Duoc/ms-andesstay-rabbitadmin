package cl.andesstay.rabbitadmin.service;

import cl.andesstay.rabbitadmin.rabbit.RabbitManagementClient;
import cl.andesstay.rabbitadmin.rabbit.RecursosRemotos.BindingRemoto;
import cl.andesstay.rabbitadmin.rabbit.RecursosRemotos.ColaRemota;
import cl.andesstay.rabbitadmin.rabbit.RecursosRemotos.ExchangeRemoto;
import cl.andesstay.rabbitadmin.service.modelo.Definiciones.DefinicionBinding;
import cl.andesstay.rabbitadmin.service.modelo.Definiciones.DefinicionCola;
import cl.andesstay.rabbitadmin.service.modelo.Definiciones.DefinicionExchange;
import cl.andesstay.rabbitadmin.service.modelo.Definiciones.EstadoCola;
import cl.andesstay.rabbitadmin.service.modelo.TipoDestino;
import cl.andesstay.rabbitadmin.service.modelo.TipoExchange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueInformation;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RabbitAdminServiceTest {

    @Mock
    private AmqpAdmin amqpAdmin;
    @Mock
    private RabbitManagementClient management;

    /** Los mismos patrones que trae application.yml por omisión. */
    private final RecursosProtegidos protegidos = new RecursosProtegidos(List.of("q.cmd.*"), List.of("cmd.*"));

    private RabbitAdminService service;

    @BeforeEach
    void crearServicio() {
        service = new RabbitAdminService(amqpAdmin, management, protegidos);
    }

    private static final DefinicionCola COLA_CON_DLQ =
            new DefinicionCola("q.prueba", true, false, "dlx.prueba", "prueba.dead", 60000L, 100L);

    // ── Colas ────────────────────────────────────────────────────────────────

    @Test
    void creaLaColaConSusArgumentosDeDeadLetterTtlYLargo() {
        when(amqpAdmin.getQueueInfo("q.prueba")).thenReturn(null, new QueueInformation("q.prueba", 0, 0));
        when(management.exchange("dlx.prueba")).thenReturn(Optional.of(exchangeRemoto("dlx.prueba")));
        when(management.cola("q.prueba")).thenReturn(Optional.of(
                new ColaRemota("q.prueba", true, false, Map.of("x-max-length", 100), 0L, 0)));

        EstadoCola estado = service.crearCola(COLA_CON_DLQ);

        ArgumentCaptor<Queue> declarada = ArgumentCaptor.forClass(Queue.class);
        verify(amqpAdmin).declareQueue(declarada.capture());
        assertThat(declarada.getValue().isDurable()).isTrue();
        assertThat(declarada.getValue().getArguments())
                .containsEntry("x-dead-letter-exchange", "dlx.prueba")
                .containsEntry("x-dead-letter-routing-key", "prueba.dead")
                .containsEntry("x-message-ttl", 60000L)
                .containsEntry("x-max-length", 100L);
        assertThat(estado.nombre()).isEqualTo("q.prueba");
    }

    @Test
    void noCreaUnaColaQueYaExiste() {
        when(amqpAdmin.getQueueInfo("q.prueba")).thenReturn(new QueueInformation("q.prueba", 0, 0));

        assertThatThrownBy(() -> service.crearCola(COLA_CON_DLQ))
                .isInstanceOf(RecursoEnConflictoException.class)
                .hasMessageContaining("Ya existe la cola 'q.prueba'");
        verify(amqpAdmin, never()).declareQueue(any());
    }

    @Test
    void noCreaUnaColaCuyoDeadLetterExchangeNoExiste() {
        // Sin esta comprobación RabbitMQ aceptaría la cola y descartaría en silencio los rechazos.
        when(amqpAdmin.getQueueInfo("q.prueba")).thenReturn(null);
        when(management.exchange("dlx.prueba")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.crearCola(COLA_CON_DLQ))
                .isInstanceOf(RecursoNoEncontradoException.class)
                .hasMessageContaining("dlx.prueba");
        verify(amqpAdmin, never()).declareQueue(any());
    }

    @Test
    void rechazaLosNombresReservados() {
        DefinicionCola reservada = new DefinicionCola("amq.mia", true, false, null, null, null, null);

        assertThatThrownBy(() -> service.crearCola(reservada))
                .isInstanceOf(OperacionNoPermitidaException.class);
        assertThatThrownBy(() -> service.eliminarExchange("amq.topic", true))
                .isInstanceOf(OperacionNoPermitidaException.class);
    }

    @Test
    void actualizarRecreaLaColaYRestauraSusBindingsEnOrden() {
        when(amqpAdmin.getQueueInfo("q.prueba")).thenReturn(new QueueInformation("q.prueba", 0, 0));
        when(management.exchange("dlx.prueba")).thenReturn(Optional.of(exchangeRemoto("dlx.prueba")));
        when(management.bindingsDeCola("q.prueba")).thenReturn(List.of(
                new BindingRemoto("cmd.direct", "q.prueba", "queue", "prueba.send", Map.of())));
        when(management.cola("q.prueba")).thenReturn(Optional.of(
                new ColaRemota("q.prueba", true, false, Map.of(), 0L, 0)));

        service.actualizarCola(COLA_CON_DLQ);

        InOrder orden = inOrder(amqpAdmin);
        orden.verify(amqpAdmin).deleteQueue("q.prueba");
        orden.verify(amqpAdmin).declareQueue(any(Queue.class));
        ArgumentCaptor<Binding> restaurado = ArgumentCaptor.forClass(Binding.class);
        orden.verify(amqpAdmin).declareBinding(restaurado.capture());
        assertThat(restaurado.getValue().getExchange()).isEqualTo("cmd.direct");
        assertThat(restaurado.getValue().getRoutingKey()).isEqualTo("prueba.send");
    }

    @Test
    void noActualizaUnaColaConMensajes() {
        when(amqpAdmin.getQueueInfo("q.prueba")).thenReturn(new QueueInformation("q.prueba", 3, 0));

        assertThatThrownBy(() -> service.actualizarCola(COLA_CON_DLQ))
                .isInstanceOf(RecursoEnConflictoException.class)
                .hasMessageContaining("3 mensaje(s)");
        verify(amqpAdmin, never()).deleteQueue(any());
    }

    @Test
    void noActualizaUnaColaConConsumidores() {
        when(amqpAdmin.getQueueInfo("q.prueba")).thenReturn(new QueueInformation("q.prueba", 0, 1));

        assertThatThrownBy(() -> service.actualizarCola(COLA_CON_DLQ))
                .isInstanceOf(RecursoEnConflictoException.class)
                .hasMessageContaining("consumidor");
        verify(amqpAdmin, never()).deleteQueue(any());
    }

    @Test
    void eliminarUnaColaInexistenteEsNoEncontrado() {
        when(amqpAdmin.getQueueInfo("q.nada")).thenReturn(null);

        assertThatThrownBy(() -> service.eliminarCola("q.nada", false))
                .isInstanceOf(RecursoNoEncontradoException.class)
                .hasMessage("No existe la cola 'q.nada'");
    }

    @Test
    void eliminarUnaColaConMensajesExigeForzar() {
        when(amqpAdmin.getQueueInfo("q.prueba")).thenReturn(new QueueInformation("q.prueba", 5, 0));

        assertThatThrownBy(() -> service.eliminarCola("q.prueba", false))
                .isInstanceOf(RecursoEnConflictoException.class)
                .hasMessageContaining("forzar=true");
        verify(amqpAdmin, never()).deleteQueue(any());

        service.eliminarCola("q.prueba", true);
        verify(amqpAdmin).deleteQueue("q.prueba");
    }

    // ── Exchanges ────────────────────────────────────────────────────────────

    @Test
    void creaUnExchangeDelTipoPedido() {
        when(management.exchange("ex.prueba")).thenReturn(Optional.empty(), Optional.of(exchangeRemoto("ex.prueba")));

        service.crearExchange(new DefinicionExchange("ex.prueba", TipoExchange.TOPIC, true, false));

        ArgumentCaptor<Exchange> declarado = ArgumentCaptor.forClass(Exchange.class);
        verify(amqpAdmin).declareExchange(declarado.capture());
        assertThat(declarado.getValue().getType()).isEqualTo("topic");
        assertThat(declarado.getValue().isDurable()).isTrue();
    }

    @Test
    void eliminarUnExchangeConBindingsExigeForzar() {
        when(management.exchange("ex.prueba")).thenReturn(Optional.of(exchangeRemoto("ex.prueba")));
        when(management.bindingsConOrigen("ex.prueba")).thenReturn(List.of(
                new BindingRemoto("ex.prueba", "q.prueba", "queue", "rk", Map.of())));
        when(management.bindingsConDestino("ex.prueba")).thenReturn(List.of());

        assertThatThrownBy(() -> service.eliminarExchange("ex.prueba", false))
                .isInstanceOf(RecursoEnConflictoException.class)
                .hasMessageContaining("1 binding(s)");
        verify(amqpAdmin, never()).deleteExchange(any());
    }

    // ── Bindings ─────────────────────────────────────────────────────────────

    @Test
    void crearUnBindingExigeQueExistaElExchange() {
        when(management.exchange("ex.nada")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.crearBinding(
                new DefinicionBinding("ex.nada", "q.prueba", TipoDestino.COLA, "rk")))
                .isInstanceOf(RecursoNoEncontradoException.class)
                .hasMessage("No existe el exchange 'ex.nada'");
        verify(amqpAdmin, never()).declareBinding(any());
    }

    @Test
    void creaUnBindingHaciaUnaCola() {
        when(management.exchange("ex.prueba")).thenReturn(Optional.of(exchangeRemoto("ex.prueba")));
        when(amqpAdmin.getQueueInfo("q.prueba")).thenReturn(new QueueInformation("q.prueba", 0, 0));
        when(management.bindingsEntre("ex.prueba", "q", "q.prueba")).thenReturn(List.of());

        service.crearBinding(new DefinicionBinding("ex.prueba", "q.prueba", TipoDestino.COLA, "rk"));

        ArgumentCaptor<Binding> declarado = ArgumentCaptor.forClass(Binding.class);
        verify(amqpAdmin).declareBinding(declarado.capture());
        assertThat(declarado.getValue().getDestination()).isEqualTo("q.prueba");
        assertThat(declarado.getValue().getDestinationType()).isEqualTo(Binding.DestinationType.QUEUE);
        assertThat(declarado.getValue().getRoutingKey()).isEqualTo("rk");
    }

    @Test
    void eliminarUnBindingInexistenteEsNoEncontrado() {
        when(management.exchange("ex.prueba")).thenReturn(Optional.of(exchangeRemoto("ex.prueba")));
        when(amqpAdmin.getQueueInfo("q.prueba")).thenReturn(new QueueInformation("q.prueba", 0, 0));
        when(management.bindingsEntre("ex.prueba", "q", "q.prueba")).thenReturn(List.of(
                new BindingRemoto("ex.prueba", "q.prueba", "queue", "otra.rk", Map.of())));

        assertThatThrownBy(() -> service.eliminarBinding(
                new DefinicionBinding("ex.prueba", "q.prueba", TipoDestino.COLA, "rk")))
                .isInstanceOf(RecursoNoEncontradoException.class);
        verify(amqpAdmin, never()).removeBinding(any());
    }

    // ── Recursos protegidos ──────────────────────────────────────────────────

    @Test
    void noPermiteCrearModificarNiEliminarUnaColaProtegida() {
        DefinicionCola sistema = new DefinicionCola("q.cmd.email", true, false, null, null, null, null);

        assertThatThrownBy(() -> service.crearCola(sistema))
                .isInstanceOf(OperacionNoPermitidaException.class)
                .hasMessageContaining("'q.cmd.email'")
                .hasMessageContaining("'q.cmd.*'")
                .hasMessageContaining("andesstay.rabbitadmin.protegidos.colas");
        assertThatThrownBy(() -> service.actualizarCola(sistema))
                .isInstanceOf(OperacionNoPermitidaException.class);
        assertThatThrownBy(() -> service.eliminarCola("q.cmd.email.dlq", false))
                .isInstanceOf(OperacionNoPermitidaException.class);
        verifyNoInteractions(amqpAdmin);
    }

    @Test
    void forzarNoSaltaLaProteccion() {
        assertThatThrownBy(() -> service.eliminarCola("q.cmd.voucher", true))
                .isInstanceOf(OperacionNoPermitidaException.class);
        assertThatThrownBy(() -> service.eliminarExchange("cmd.dead.dlx", true))
                .isInstanceOf(OperacionNoPermitidaException.class)
                .hasMessageContaining("'cmd.*'");
        verifyNoInteractions(amqpAdmin);
    }

    @Test
    void noPermiteModificarUnExchangeProtegido() {
        assertThatThrownBy(() -> service.actualizarExchange(
                new DefinicionExchange("cmd.direct", TipoExchange.TOPIC, true, false)))
                .isInstanceOf(OperacionNoPermitidaException.class);
        verifyNoInteractions(amqpAdmin);
    }

    @Test
    void permiteUnBindingDesdeUnExchangeProtegidoHaciaUnaColaNueva() {
        when(management.exchange("cmd.topic")).thenReturn(Optional.of(exchangeRemoto("cmd.topic")));
        when(amqpAdmin.getQueueInfo("q.demo.auditoria")).thenReturn(new QueueInformation("q.demo.auditoria", 0, 0));
        when(management.bindingsEntre("cmd.topic", "q", "q.demo.auditoria")).thenReturn(List.of());

        service.crearBinding(new DefinicionBinding("cmd.topic", "q.demo.auditoria", TipoDestino.COLA, "email.*"));

        verify(amqpAdmin).declareBinding(any(Binding.class));
    }

    @Test
    void noPermiteUnBindingHaciaUnaColaProtegida() {
        assertThatThrownBy(() -> service.crearBinding(
                new DefinicionBinding("ex.demo", "q.cmd.email", TipoDestino.COLA, "rk")))
                .isInstanceOf(OperacionNoPermitidaException.class)
                .hasMessageContaining("La cola de destino 'q.cmd.email'");
        verifyNoInteractions(amqpAdmin);
    }

    @Test
    void noPermiteUnBindingHaciaUnExchangeProtegido() {
        assertThatThrownBy(() -> service.crearBinding(
                new DefinicionBinding("ex.demo", "cmd.direct", TipoDestino.EXCHANGE, "rk")))
                .isInstanceOf(OperacionNoPermitidaException.class)
                .hasMessageContaining("El exchange de destino 'cmd.direct'");
        verifyNoInteractions(amqpAdmin);
    }

    @Test
    void noPermiteEliminarUnBindingQueSaleDeUnExchangeProtegido() {
        // Aunque el destino sea una cola nueva: el binding se pudo crear, pero quitarlo de un
        // exchange del sistema queda reservado a la configuración.
        assertThatThrownBy(() -> service.eliminarBinding(
                new DefinicionBinding("cmd.direct", "q.demo.auditoria", TipoDestino.COLA, "email.send")))
                .isInstanceOf(OperacionNoPermitidaException.class)
                .hasMessageContaining("El exchange de origen 'cmd.direct'");
        verifyNoInteractions(amqpAdmin);
    }

    private static ExchangeRemoto exchangeRemoto(String nombre) {
        return new ExchangeRemoto(nombre, "direct", true, false, false);
    }
}
