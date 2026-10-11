package cl.andesstay.rabbitadmin;

import cl.andesstay.rabbitadmin.rabbit.RabbitManagementClient;
import cl.andesstay.rabbitadmin.service.RabbitAdminService;
import cl.andesstay.rabbitadmin.service.RecursosProtegidos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El contexto completo arranca sin broker: la conexión AMQP y el decodificador de JWT son
 * perezosos. Detecta errores de cableado (un bean que falta, una propiedad sin valor) que los
 * tests de capa no ven.
 *
 * También comprueba la protección con los valores que de verdad carga application.yml, no con una
 * copia: si alguien cambia el patrón por omisión y deja fuera un recurso del sistema, falla aquí.
 */
@SpringBootTest
class RabbitAdminApplicationTest {

    @Autowired
    private RabbitAdminService service;
    @Autowired
    private RabbitManagementClient management;
    @Autowired
    private RecursosProtegidos protegidos;

    @Test
    void elContextoArrancaConTodosLosComponentes() {
        assertThat(service).isNotNull();
        assertThat(management).isNotNull();
    }

    /**
     * Las seis colas que declara ms-andesstay-notify en su application.yml
     * (andesstay.rabbitmq.queues.*), con las tres DLQ. Las .dlq son el caso que la semántica de
     * prefijo existe para cubrir: con el '*' de un binding topic no calzarían con q.cmd.*.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "q.cmd.email", "q.cmd.email.dlq",
            "q.cmd.housekeeping", "q.cmd.housekeeping.dlq",
            "q.cmd.voucher", "q.cmd.voucher.dlq"})
    void protegeLasSeisColasRealesDelSistema(String cola) {
        assertThat(protegidos.patronDeCola(cola)).contains("q.cmd.*");
    }

    /**
     * Los tres exchanges que declaran ms-andesstay-notify (andesstay.rabbitmq.exchange.*) y
     * ms-andesstay-reservations (andesstay.rabbitmq.exchange-*). cmd.dead.dlx tiene dos palabras
     * después de "cmd.", igual que las DLQ.
     */
    @ParameterizedTest
    @ValueSource(strings = {"cmd.direct", "cmd.topic", "cmd.dead.dlx"})
    void protegeLosTresExchangesRealesDelSistema(String exchange) {
        assertThat(protegidos.patronDeExchange(exchange)).contains("cmd.*");
    }

    /** Nombres parecidos que NO son del sistema y deben poder administrarse. */
    @ParameterizedTest
    @ValueSource(strings = {"q.demo.email", "q.cmdx", "q.cmd", "xq.cmd.email", "cmd", "cmdx.direct"})
    void noProtegeNombresQueSoloSeParecen(String nombre) {
        assertThat(protegidos.patronDeCola(nombre)).isEmpty();
        assertThat(protegidos.patronDeExchange(nombre)).isEmpty();
    }

    /**
     * Un nombre nuevo bajo el prefijo también queda protegido aunque hoy no exista: el espacio de
     * nombres q.cmd.* es del sistema, y así una cola que notify agregue mañana nace protegida. Se
     * deja escrito para que sea una decisión y no un accidente.
     */
    @Test
    void unNombreNuevoBajoElPrefijoTambienQuedaProtegido() {
        assertThat(protegidos.patronDeCola("q.cmd.sms")).contains("q.cmd.*");
        assertThat(protegidos.patronDeExchange("cmd.headers")).contains("cmd.*");
    }
}
