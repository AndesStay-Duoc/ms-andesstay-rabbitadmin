package cl.andesstay.rabbitadmin.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.DefaultUriBuilderFactory;

/**
 * Cliente HTTP hacia la API de gestión de RabbitMQ.
 *
 * Es la única configuración que este servicio necesita además de la que Spring Boot ya arma para
 * AMQP (la conexión y el AmqpAdmin salen de spring.rabbitmq).
 */
@Configuration
public class RabbitManagementConfig {

    /**
     * El modo VALUES_ONLY codifica TODO el valor de cada variable de la URL, incluida la barra. Con
     * el modo por omisión, un vhost "/" o un nombre de cola con caracteres reservados se colaría
     * como parte de la ruta y la API respondería con otro recurso o con 404.
     */
    @Bean
    public RestClient rabbitManagementRestClient(
            RestClient.Builder builder,
            @Value("${andesstay.rabbitadmin.management.url}") String url,
            @Value("${andesstay.rabbitadmin.management.timeout-ms}") int timeoutMs,
            @Value("${spring.rabbitmq.username}") String usuario,
            @Value("${spring.rabbitmq.password}") String password) {

        DefaultUriBuilderFactory uris = new DefaultUriBuilderFactory(url);
        uris.setEncodingMode(DefaultUriBuilderFactory.EncodingMode.VALUES_ONLY);

        // Sin timeouts, un broker colgado dejaría la petición HTTP del administrador esperando
        // indefinidamente.
        SimpleClientHttpRequestFactory http = new SimpleClientHttpRequestFactory();
        http.setConnectTimeout(timeoutMs);
        http.setReadTimeout(timeoutMs);

        return builder
                .uriBuilderFactory(uris)
                .requestFactory(http)
                .defaultHeaders(h -> h.setBasicAuth(usuario, password))
                .build();
    }
}
