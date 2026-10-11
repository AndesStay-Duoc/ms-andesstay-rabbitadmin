package cl.andesstay.rabbitadmin.rabbit;

import cl.andesstay.rabbitadmin.rabbit.RecursosRemotos.BindingRemoto;
import cl.andesstay.rabbitadmin.rabbit.RecursosRemotos.ColaRemota;
import cl.andesstay.rabbitadmin.rabbit.RecursosRemotos.ExchangeRemoto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Lecturas sobre la API HTTP de gestión de RabbitMQ.
 *
 * Solo lee. Toda escritura pasa por AMQP con AmqpAdmin, que es la vía que Spring AMQP soporta y la
 * que usan el resto de los microservicios; la API HTTP se usa únicamente para lo que AMQP no ofrece:
 * listar recursos, leer los argumentos de una cola y conocer los bindings existentes.
 *
 * Los listados excluyen el exchange por omisión ("") y sus bindings implícitos: RabbitMQ los crea
 * solo, no se pueden borrar y mostrarlos confundiría.
 */
@Component
public class RabbitManagementClient {

    private static final ParameterizedTypeReference<List<ColaRemota>> LISTA_COLAS = new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<List<ExchangeRemoto>> LISTA_EXCHANGES = new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<List<BindingRemoto>> LISTA_BINDINGS = new ParameterizedTypeReference<>() { };

    private final RestClient http;
    private final String vhost;

    public RabbitManagementClient(RestClient rabbitManagementRestClient,
                                  @Value("${spring.rabbitmq.virtual-host}") String vhost) {
        this.http = rabbitManagementRestClient;
        this.vhost = vhost;
    }

    // ── Colas ────────────────────────────────────────────────────────────────

    public List<ColaRemota> colas() {
        return lista(() -> http.get().uri("/api/queues/{vhost}", vhost).retrieve().body(LISTA_COLAS));
    }

    public Optional<ColaRemota> cola(String nombre) {
        return unico(() -> http.get().uri("/api/queues/{vhost}/{cola}", vhost, nombre)
                .retrieve().body(ColaRemota.class));
    }

    /** Bindings en que la cola es el destino, sin el implícito del exchange por omisión. */
    public List<BindingRemoto> bindingsDeCola(String cola) {
        return sinExchangePorOmision(lista(() -> http.get()
                .uri("/api/queues/{vhost}/{cola}/bindings", vhost, cola)
                .retrieve().body(LISTA_BINDINGS)));
    }

    // ── Exchanges ────────────────────────────────────────────────────────────

    public List<ExchangeRemoto> exchanges() {
        return lista(() -> http.get().uri("/api/exchanges/{vhost}", vhost).retrieve().body(LISTA_EXCHANGES))
                .stream()
                .filter(e -> e.name() != null && !e.name().isEmpty())
                .toList();
    }

    public Optional<ExchangeRemoto> exchange(String nombre) {
        return unico(() -> http.get().uri("/api/exchanges/{vhost}/{exchange}", vhost, nombre)
                .retrieve().body(ExchangeRemoto.class));
    }

    /** Bindings que salen de este exchange, hacia colas o hacia otros exchanges. */
    public List<BindingRemoto> bindingsConOrigen(String exchange) {
        return lista(() -> http.get()
                .uri("/api/exchanges/{vhost}/{exchange}/bindings/source", vhost, exchange)
                .retrieve().body(LISTA_BINDINGS));
    }

    /** Bindings que llegan a este exchange desde otro exchange. */
    public List<BindingRemoto> bindingsConDestino(String exchange) {
        return lista(() -> http.get()
                .uri("/api/exchanges/{vhost}/{exchange}/bindings/destination", vhost, exchange)
                .retrieve().body(LISTA_BINDINGS));
    }

    // ── Bindings ─────────────────────────────────────────────────────────────

    public List<BindingRemoto> bindings() {
        return sinExchangePorOmision(lista(() -> http.get()
                .uri("/api/bindings/{vhost}", vhost).retrieve().body(LISTA_BINDINGS)));
    }

    /**
     * Bindings entre un exchange y un destino concreto.
     *
     * @param tipoDestino "q" para cola o "e" para exchange, que es como lo nombra la API
     */
    public List<BindingRemoto> bindingsEntre(String exchange, String tipoDestino, String destino) {
        return lista(() -> http.get()
                .uri("/api/bindings/{vhost}/e/{exchange}/{tipo}/{destino}", vhost, exchange, tipoDestino, destino)
                .retrieve().body(LISTA_BINDINGS));
    }

    // ── Interno ──────────────────────────────────────────────────────────────

    /** Un 404 de la API significa "no existe", no un fallo: se traduce a Optional vacío. */
    private static <T> Optional<T> unico(Supplier<T> llamada) {
        try {
            return Optional.ofNullable(llamada.get());
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }

    /** Para un listado, un 404 significa que el recurso padre no existe: lista vacía. */
    private static <T> List<T> lista(Supplier<List<T>> llamada) {
        try {
            List<T> resultado = llamada.get();
            return resultado == null ? List.of() : resultado;
        } catch (HttpClientErrorException.NotFound e) {
            return List.of();
        }
    }

    private static List<BindingRemoto> sinExchangePorOmision(List<BindingRemoto> bindings) {
        return bindings.stream()
                .filter(b -> b.source() != null && !b.source().isEmpty())
                .toList();
    }
}
