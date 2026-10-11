package cl.andesstay.rabbitadmin.service;

import cl.andesstay.rabbitadmin.rabbit.RabbitManagementClient;
import cl.andesstay.rabbitadmin.rabbit.RecursosRemotos.BindingRemoto;
import cl.andesstay.rabbitadmin.rabbit.RecursosRemotos.ColaRemota;
import cl.andesstay.rabbitadmin.rabbit.RecursosRemotos.ExchangeRemoto;
import cl.andesstay.rabbitadmin.service.modelo.Definiciones.DefinicionBinding;
import cl.andesstay.rabbitadmin.service.modelo.Definiciones.DefinicionCola;
import cl.andesstay.rabbitadmin.service.modelo.Definiciones.DefinicionExchange;
import cl.andesstay.rabbitadmin.service.modelo.Definiciones.EstadoBinding;
import cl.andesstay.rabbitadmin.service.modelo.Definiciones.EstadoCola;
import cl.andesstay.rabbitadmin.service.modelo.Definiciones.EstadoExchange;
import cl.andesstay.rabbitadmin.service.modelo.TipoDestino;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Crea, consulta, actualiza y elimina colas, exchanges y bindings de RabbitMQ.
 *
 * Es el único punto del servicio que conoce RabbitMQ. Los controladores le pasan y reciben tipos de
 * service.modelo y nunca ven AmqpAdmin, la API de gestión ni sus excepciones de canal.
 *
 * Reparto de vías:
 *   escritura (declarar, eliminar)            -> AMQP, con AmqpAdmin
 *   conteo exacto de mensajes y consumidores  -> AMQP, con AmqpAdmin.getQueueInfo
 *   listados, argumentos y bindings existentes -> API HTTP de gestión, con RabbitManagementClient
 *
 * Sobre "actualizar": RabbitMQ no permite modificar una cola o un exchange ya declarado; volver a
 * declararlo con otros argumentos falla con PRECONDITION_FAILED. La única actualización posible es
 * eliminar y recrear, y eliminar arrastra los bindings. Por eso actualizar lee primero los bindings,
 * recrea el recurso y los restaura. No es atómico: si el broker cae a mitad de camino, el log deja
 * escritos los bindings que faltó restaurar.
 *
 * Reglas de protección, todas con un error explícito en lugar de un efecto silencioso:
 *   - nombres reservados ("amq.*" y el exchange por omisión): OperacionNoPermitidaException
 *   - recursos protegidos por configuración (ver RecursosProtegidos), la topología de la que
 *     dependen los demás microservicios: OperacionNoPermitidaException, sin opción de forzar
 *   - eliminar una cola con mensajes o consumidores, o un exchange con bindings, sin forzar:
 *     RecursoEnConflictoException
 *   - actualizar una cola con mensajes o consumidores: RecursoEnConflictoException, siempre. No hay
 *     forma de recrearla sin perder los mensajes, así que aquí no existe la opción de forzar.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RabbitAdminService {

    private static final String PREFIJO_RESERVADO = "amq.";
    private static final String COLA = "la cola";
    private static final String EXCHANGE = "el exchange";
    private static final String BINDING = "el binding";

    private final AmqpAdmin amqpAdmin;
    private final RabbitManagementClient management;
    private final RecursosProtegidos protegidos;

    // ── Colas ────────────────────────────────────────────────────────────────

    /** Los conteos de un listado vienen de las estadísticas de la API y pueden ir unos segundos atrás. */
    public List<EstadoCola> listarColas() {
        return management.colas().stream().map(RabbitAdminService::aEstado).toList();
    }

    public EstadoCola consultarCola(String nombre) {
        QueueInformation info = exigirCola(nombre);
        ColaRemota remota = management.cola(nombre)
                .orElseThrow(() -> new RecursoNoEncontradoException(COLA, nombre));
        return new EstadoCola(nombre, remota.durable(), remota.autoDelete(),
                argumentos(remota.arguments()), info.getMessageCount(), info.getConsumerCount());
    }

    public EstadoCola crearCola(DefinicionCola definicion) {
        String nombre = definicion.nombre();
        rechazarReservado(COLA, nombre);
        rechazarColaProtegida(nombre, "crear");
        if (amqpAdmin.getQueueInfo(nombre) != null) {
            throw new RecursoEnConflictoException("Ya existe la cola '%s'".formatted(nombre));
        }
        exigirDeadLetterExchange(definicion);

        amqpAdmin.declareQueue(construirCola(definicion));
        log.info("[Admin] Cola creada: {}", definicion);
        return consultarCola(nombre);
    }

    /**
     * Recrea la cola con la nueva definición y le devuelve sus bindings.
     *
     * Solo procede con la cola vacía y sin consumidores: eliminarla descartaría los mensajes y
     * cortaría la suscripción de quien la esté consumiendo.
     */
    public EstadoCola actualizarCola(DefinicionCola definicion) {
        String nombre = definicion.nombre();
        rechazarReservado(COLA, nombre);
        rechazarColaProtegida(nombre, "modificar");
        QueueInformation info = exigirCola(nombre);
        if (info.getMessageCount() > 0) {
            throw new RecursoEnConflictoException(
                    "La cola '%s' tiene %d mensaje(s): para actualizarla hay que recrearla y se perderían. Vacíela antes."
                            .formatted(nombre, info.getMessageCount()));
        }
        if (info.getConsumerCount() > 0) {
            throw new RecursoEnConflictoException(
                    "La cola '%s' tiene %d consumidor(es) conectado(s): recrearla les cortaría la suscripción. Deténgalos antes."
                            .formatted(nombre, info.getConsumerCount()));
        }
        exigirDeadLetterExchange(definicion);

        List<BindingRemoto> bindings = management.bindingsDeCola(nombre);
        amqpAdmin.deleteQueue(nombre);
        amqpAdmin.declareQueue(construirCola(definicion));
        restaurar(bindings, COLA + " " + nombre);

        log.warn("[Admin] Cola recreada: {} · {} binding(s) restaurado(s)", definicion, bindings.size());
        return consultarCola(nombre);
    }

    /** @param forzar elimina aunque la cola tenga mensajes o consumidores */
    public void eliminarCola(String nombre, boolean forzar) {
        rechazarReservado(COLA, nombre);
        rechazarColaProtegida(nombre, "eliminar");
        QueueInformation info = exigirCola(nombre);
        if (!forzar && info.getMessageCount() > 0) {
            throw new RecursoEnConflictoException(
                    "La cola '%s' tiene %d mensaje(s) que se perderían. Use forzar=true para eliminarla igual."
                            .formatted(nombre, info.getMessageCount()));
        }
        if (!forzar && info.getConsumerCount() > 0) {
            throw new RecursoEnConflictoException(
                    "La cola '%s' tiene %d consumidor(es) conectado(s). Use forzar=true para eliminarla igual."
                            .formatted(nombre, info.getConsumerCount()));
        }

        amqpAdmin.deleteQueue(nombre);
        log.warn("[Admin] Cola eliminada: {} (mensajes descartados={}, forzar={})",
                nombre, info.getMessageCount(), forzar);
    }

    // ── Exchanges ────────────────────────────────────────────────────────────

    public List<EstadoExchange> listarExchanges() {
        return management.exchanges().stream().map(RabbitAdminService::aEstado).toList();
    }

    public EstadoExchange consultarExchange(String nombre) {
        return aEstado(exigirExchange(nombre));
    }

    public EstadoExchange crearExchange(DefinicionExchange definicion) {
        String nombre = definicion.nombre();
        rechazarReservado(EXCHANGE, nombre);
        rechazarExchangeProtegido(nombre, "crear");
        if (management.exchange(nombre).isPresent()) {
            throw new RecursoEnConflictoException("Ya existe el exchange '%s'".formatted(nombre));
        }

        amqpAdmin.declareExchange(construirExchange(definicion));
        log.info("[Admin] Exchange creado: {}", definicion);
        return consultarExchange(nombre);
    }

    /**
     * Recrea el exchange y le devuelve los bindings que salían y llegaban a él.
     *
     * Un exchange no guarda mensajes, así que no hay nada que perder en el broker; pero lo que se
     * publique en el instante entre la eliminación y la recreación no tendrá a dónde ir.
     */
    public EstadoExchange actualizarExchange(DefinicionExchange definicion) {
        String nombre = definicion.nombre();
        rechazarReservado(EXCHANGE, nombre);
        rechazarExchangeProtegido(nombre, "modificar");
        exigirExchange(nombre);

        List<BindingRemoto> bindings = bindingsDeExchange(nombre);
        amqpAdmin.deleteExchange(nombre);
        amqpAdmin.declareExchange(construirExchange(definicion));
        restaurar(bindings, EXCHANGE + " " + nombre);

        log.warn("[Admin] Exchange recreado: {} · {} binding(s) restaurado(s)", definicion, bindings.size());
        return consultarExchange(nombre);
    }

    /** @param forzar elimina aunque el exchange tenga bindings, que se pierden con él */
    public void eliminarExchange(String nombre, boolean forzar) {
        rechazarReservado(EXCHANGE, nombre);
        rechazarExchangeProtegido(nombre, "eliminar");
        exigirExchange(nombre);
        List<BindingRemoto> bindings = bindingsDeExchange(nombre);
        if (!forzar && !bindings.isEmpty()) {
            throw new RecursoEnConflictoException(
                    "El exchange '%s' tiene %d binding(s) que se eliminarían con él. Use forzar=true para eliminarlo igual."
                            .formatted(nombre, bindings.size()));
        }

        amqpAdmin.deleteExchange(nombre);
        log.warn("[Admin] Exchange eliminado: {} (bindings eliminados={}, forzar={})",
                nombre, bindings.size(), forzar);
    }

    // ── Bindings ─────────────────────────────────────────────────────────────

    public List<EstadoBinding> listarBindings() {
        return management.bindings().stream().map(RabbitAdminService::aEstado).toList();
    }

    /**
     * Se permite crear un binding DESDE un exchange protegido hacia una cola nueva: solo agrega
     * una copia de los mensajes y no le quita nada a la topología. No se permite HACIA un recurso
     * protegido, porque metería mensajes ajenos en una cola del sistema.
     */
    public EstadoBinding crearBinding(DefinicionBinding definicion) {
        rechazarDestinoProtegido(definicion, "crear");
        exigirExtremos(definicion);
        if (bindingExistente(definicion) != null) {
            throw new RecursoEnConflictoException("Ya existe el binding %s".formatted(describir(definicion)));
        }

        amqpAdmin.declareBinding(construirBinding(definicion, null));
        log.info("[Admin] Binding creado: {}", describir(definicion));
        // Un binding no tiene más atributos que los de su definición: releerlo de la API sería una
        // llamada HTTP sin información nueva.
        return new EstadoBinding(definicion.exchange(), definicion.destino(),
                definicion.tipoDestino(), routingKey(definicion));
    }

    /**
     * No se puede eliminar un binding que salga de un exchange protegido ni que llegue a un recurso
     * protegido: quitar, por ejemplo, cmd.direct -> q.cmd.email con email.send cortaría el flujo de
     * correos sin borrar nada visible.
     */
    public void eliminarBinding(DefinicionBinding definicion) {
        protegidos.patronDeExchange(definicion.exchange()).ifPresent(patron -> {
            throw protegido("el exchange de origen", definicion.exchange(), patron,
                    "andesstay.rabbitadmin.protegidos.exchanges", "eliminar sus bindings");
        });
        rechazarDestinoProtegido(definicion, "eliminar");
        exigirExtremos(definicion);
        BindingRemoto actual = bindingExistente(definicion);
        if (actual == null) {
            throw new RecursoNoEncontradoException(BINDING, describir(definicion));
        }

        // Los argumentos tienen que coincidir con los del binding existente o RabbitMQ no lo
        // reconoce y el unbind no tiene efecto.
        amqpAdmin.removeBinding(construirBinding(definicion, actual.arguments()));
        log.warn("[Admin] Binding eliminado: {}", describir(definicion));
    }

    // ── Comprobaciones ───────────────────────────────────────────────────────

    private static void rechazarReservado(String tipo, String nombre) {
        if (nombre == null || nombre.isBlank()) {
            throw new IllegalArgumentException("El nombre de %s no puede estar vacío".formatted(tipo));
        }
        if (nombre.startsWith(PREFIJO_RESERVADO)) {
            throw new OperacionNoPermitidaException(
                    "'%s' es un nombre reservado de RabbitMQ: los nombres que empiezan con '%s' no se pueden crear, modificar ni eliminar"
                            .formatted(nombre, PREFIJO_RESERVADO));
        }
    }

    private void rechazarColaProtegida(String nombre, String accion) {
        protegidos.patronDeCola(nombre).ifPresent(patron -> {
            throw protegido(COLA, nombre, patron, "andesstay.rabbitadmin.protegidos.colas", accion + "la");
        });
    }

    private void rechazarExchangeProtegido(String nombre, String accion) {
        protegidos.patronDeExchange(nombre).ifPresent(patron -> {
            throw protegido(EXCHANGE, nombre, patron, "andesstay.rabbitadmin.protegidos.exchanges", accion + "lo");
        });
    }

    private void rechazarDestinoProtegido(DefinicionBinding definicion, String accion) {
        if (definicion.tipoDestino() == TipoDestino.EXCHANGE) {
            protegidos.patronDeExchange(definicion.destino()).ifPresent(patron -> {
                throw protegido("el exchange de destino", definicion.destino(), patron,
                        "andesstay.rabbitadmin.protegidos.exchanges", accion + " bindings hacia él");
            });
        } else {
            protegidos.patronDeCola(definicion.destino()).ifPresent(patron -> {
                throw protegido("la cola de destino", definicion.destino(), patron,
                        "andesstay.rabbitadmin.protegidos.colas", accion + " bindings hacia ella");
            });
        }
    }

    private static OperacionNoPermitidaException protegido(String tipo, String nombre, String patron,
                                                           String propiedad, String accion) {
        return new OperacionNoPermitidaException(
                "%s '%s' coincide con el patrón protegido '%s' (%s): forma parte de la topología de la que dependen otros microservicios y no se puede %s desde el administrador"
                        .formatted(capitalizar(tipo), nombre, patron, propiedad, accion));
    }

    private static String capitalizar(String texto) {
        return Character.toUpperCase(texto.charAt(0)) + texto.substring(1);
    }

    private QueueInformation exigirCola(String nombre) {
        QueueInformation info = amqpAdmin.getQueueInfo(nombre);
        if (info == null) {
            throw new RecursoNoEncontradoException(COLA, nombre);
        }
        return info;
    }

    private ExchangeRemoto exigirExchange(String nombre) {
        return management.exchange(nombre)
                .orElseThrow(() -> new RecursoNoEncontradoException(EXCHANGE, nombre));
    }

    /**
     * RabbitMQ acepta una cola con un dead letter exchange inexistente, pero entonces descarta en
     * silencio cada mensaje rechazado: exactamente lo que una DLQ pretende evitar.
     */
    private void exigirDeadLetterExchange(DefinicionCola definicion) {
        if (definicion.deadLetterExchange() != null) {
            exigirExchange(definicion.deadLetterExchange());
        }
    }

    private void exigirExtremos(DefinicionBinding definicion) {
        exigirExchange(definicion.exchange());
        if (definicion.tipoDestino() == TipoDestino.EXCHANGE) {
            exigirExchange(definicion.destino());
        } else {
            exigirCola(definicion.destino());
        }
    }

    private BindingRemoto bindingExistente(DefinicionBinding definicion) {
        String tipoApi = definicion.tipoDestino() == TipoDestino.EXCHANGE ? "e" : "q";
        String routingKey = routingKey(definicion);
        return management.bindingsEntre(definicion.exchange(), tipoApi, definicion.destino()).stream()
                .filter(b -> routingKey.equals(b.routingKey()))
                .findFirst()
                .orElse(null);
    }

    private List<BindingRemoto> bindingsDeExchange(String nombre) {
        List<BindingRemoto> todos = new ArrayList<>(management.bindingsConOrigen(nombre));
        todos.addAll(management.bindingsConDestino(nombre));
        return todos;
    }

    // ── Construcción ─────────────────────────────────────────────────────────

    private static Queue construirCola(DefinicionCola d) {
        QueueBuilder builder = d.durable() ? QueueBuilder.durable(d.nombre()) : QueueBuilder.nonDurable(d.nombre());
        if (d.autoEliminar()) {
            builder.autoDelete();
        }
        if (d.deadLetterExchange() != null) {
            builder.deadLetterExchange(d.deadLetterExchange());
        }
        if (d.deadLetterRoutingKey() != null) {
            builder.deadLetterRoutingKey(d.deadLetterRoutingKey());
        }
        if (d.ttlMensajesMs() != null) {
            builder.withArgument("x-message-ttl", d.ttlMensajesMs());
        }
        if (d.maxMensajes() != null) {
            builder.withArgument("x-max-length", d.maxMensajes());
        }
        return builder.build();
    }

    private static Exchange construirExchange(DefinicionExchange d) {
        ExchangeBuilder builder = new ExchangeBuilder(d.nombre(), d.tipo().valorAmqp()).durable(d.durable());
        if (d.autoEliminar()) {
            builder.autoDelete();
        }
        return builder.build();
    }

    private static Binding construirBinding(DefinicionBinding d, Map<String, Object> argumentos) {
        Binding.DestinationType tipo = d.tipoDestino() == TipoDestino.EXCHANGE
                ? Binding.DestinationType.EXCHANGE
                : Binding.DestinationType.QUEUE;
        return new Binding(d.destino(), tipo, d.exchange(), routingKey(d), argumentos);
    }

    /**
     * Vuelve a declarar los bindings leídos antes de recrear un recurso. Si alguno falla se deja en
     * el log la lista de los que no se restauraron, para poder declararlos a mano, y se propaga el
     * error: devolver un 200 con bindings perdidos sería un éxito aparente.
     */
    private void restaurar(List<BindingRemoto> bindings, String recurso) {
        for (int i = 0; i < bindings.size(); i++) {
            BindingRemoto b = bindings.get(i);
            Binding.DestinationType tipo = BindingRemoto.DESTINO_EXCHANGE.equals(b.destinationType())
                    ? Binding.DestinationType.EXCHANGE
                    : Binding.DestinationType.QUEUE;
            try {
                amqpAdmin.declareBinding(new Binding(b.destination(), tipo, b.source(), b.routingKey(), b.arguments()));
            } catch (RuntimeException e) {
                log.error("[Admin] Se recreó {} pero falló la restauración de sus bindings. Pendientes: {}",
                        recurso, bindings.subList(i, bindings.size()));
                throw e;
            }
        }
    }

    // ── Traducción al modelo propio ──────────────────────────────────────────

    private static EstadoCola aEstado(ColaRemota r) {
        return new EstadoCola(r.name(), r.durable(), r.autoDelete(), argumentos(r.arguments()),
                r.messages() == null ? 0 : r.messages(),
                r.consumers() == null ? 0 : r.consumers());
    }

    private static EstadoExchange aEstado(ExchangeRemoto r) {
        return new EstadoExchange(r.name(), r.type(), r.durable(), r.autoDelete());
    }

    private static EstadoBinding aEstado(BindingRemoto r) {
        TipoDestino tipo = BindingRemoto.DESTINO_EXCHANGE.equals(r.destinationType())
                ? TipoDestino.EXCHANGE
                : TipoDestino.COLA;
        return new EstadoBinding(r.source(), r.destination(), tipo, r.routingKey());
    }

    private static Map<String, Object> argumentos(Map<String, Object> argumentos) {
        return argumentos == null ? Map.of() : argumentos;
    }

    private static String routingKey(DefinicionBinding d) {
        return d.routingKey() == null ? "" : d.routingKey();
    }

    private static String describir(DefinicionBinding d) {
        return "%s -> %s %s (routingKey='%s')".formatted(
                d.exchange(), d.tipoDestino() == TipoDestino.EXCHANGE ? "exchange" : "cola",
                d.destino(), routingKey(d));
    }
}
