package cl.andesstay.rabbitadmin.controller;

import cl.andesstay.rabbitadmin.service.RabbitAdminService;
import cl.andesstay.rabbitadmin.web.dto.ExchangeDtos.ActualizarExchangeRequest;
import cl.andesstay.rabbitadmin.web.dto.ExchangeDtos.CrearExchangeRequest;
import cl.andesstay.rabbitadmin.web.dto.ExchangeDtos.ExchangeResponse;
import cl.andesstay.rabbitadmin.web.dto.NombreRecurso;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.List;

/**
 * Exchanges del vhost configurado, sin el exchange por omisión.
 *
 *   GET    /api/v1/admin/exchanges              200  lista
 *   GET    /api/v1/admin/exchanges/{nombre}     200  detalle                    | 404
 *   POST   /api/v1/admin/exchanges              201  + Location                 | 409 si ya existe
 *   PUT    /api/v1/admin/exchanges/{nombre}     200  recrea conservando bindings | 404
 *   DELETE /api/v1/admin/exchanges/{nombre}     204                             | 404 | 409 si tiene bindings
 *          ?forzar=true                              elimina igual, con sus bindings
 *
 * Los nombres "amq.*" responden 403 OPERACION_NO_PERMITIDA en POST, PUT y DELETE.
 */
@RestController
@RequestMapping(ExchangeController.RUTA)
@RequiredArgsConstructor
public class ExchangeController {

    public static final String RUTA = "/api/v1/admin/exchanges";

    private final RabbitAdminService service;

    @GetMapping
    public List<ExchangeResponse> listar() {
        return service.listarExchanges().stream().map(ExchangeResponse::desde).toList();
    }

    @GetMapping("/{nombre}")
    public ExchangeResponse consultar(@PathVariable @NombreRecurso String nombre) {
        return ExchangeResponse.desde(service.consultarExchange(nombre));
    }

    @PostMapping
    public ResponseEntity<ExchangeResponse> crear(@Valid @RequestBody CrearExchangeRequest peticion) {
        ExchangeResponse creado = ExchangeResponse.desde(service.crearExchange(peticion.aDefinicion()));
        return ResponseEntity
                .created(ServletUriComponentsBuilder.fromCurrentRequest()
                        .path("/{nombre}").buildAndExpand(creado.nombre()).toUri())
                .body(creado);
    }

    @PutMapping("/{nombre}")
    public ExchangeResponse actualizar(@PathVariable @NombreRecurso String nombre, @Valid @RequestBody ActualizarExchangeRequest peticion) {
        return ExchangeResponse.desde(service.actualizarExchange(peticion.aDefinicion(nombre)));
    }

    @DeleteMapping("/{nombre}")
    public ResponseEntity<Void> eliminar(@PathVariable @NombreRecurso String nombre,
                                         @RequestParam(defaultValue = "false") boolean forzar) {
        service.eliminarExchange(nombre, forzar);
        return ResponseEntity.noContent().build();
    }
}
