package cl.andesstay.rabbitadmin.controller;

import cl.andesstay.rabbitadmin.service.RabbitAdminService;
import cl.andesstay.rabbitadmin.web.dto.ColaDtos.ActualizarColaRequest;
import cl.andesstay.rabbitadmin.web.dto.ColaDtos.ColaResponse;
import cl.andesstay.rabbitadmin.web.dto.ColaDtos.CrearColaRequest;
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
 * Colas del vhost configurado.
 *
 *   GET    /api/v1/admin/queues                 200  lista
 *   GET    /api/v1/admin/queues/{nombre}        200  detalle con conteo exacto | 404
 *   POST   /api/v1/admin/queues                 201  + Location                | 409 si ya existe
 *   PUT    /api/v1/admin/queues/{nombre}        200  recrea conservando bindings | 404 | 409 si tiene mensajes o consumidores
 *   DELETE /api/v1/admin/queues/{nombre}        204                             | 404 | 409 si tiene mensajes o consumidores
 *          ?forzar=true                              elimina igual
 *
 * Todas exigen el rol Admin (ver SecurityConfig). Los códigos de error los fija ManejadorDeErrores.
 */
@RestController
@RequestMapping(ColaController.RUTA)
@RequiredArgsConstructor
public class ColaController {

    public static final String RUTA = "/api/v1/admin/queues";

    private final RabbitAdminService service;

    @GetMapping
    public List<ColaResponse> listar() {
        return service.listarColas().stream().map(ColaResponse::desde).toList();
    }

    @GetMapping("/{nombre}")
    public ColaResponse consultar(@PathVariable @NombreRecurso String nombre) {
        return ColaResponse.desde(service.consultarCola(nombre));
    }

    @PostMapping
    public ResponseEntity<ColaResponse> crear(@Valid @RequestBody CrearColaRequest peticion) {
        ColaResponse creada = ColaResponse.desde(service.crearCola(peticion.aDefinicion()));
        return ResponseEntity
                .created(ServletUriComponentsBuilder.fromCurrentRequest()
                        .path("/{nombre}").buildAndExpand(creada.nombre()).toUri())
                .body(creada);
    }

    @PutMapping("/{nombre}")
    public ColaResponse actualizar(@PathVariable @NombreRecurso String nombre, @Valid @RequestBody ActualizarColaRequest peticion) {
        return ColaResponse.desde(service.actualizarCola(peticion.aDefinicion(nombre)));
    }

    @DeleteMapping("/{nombre}")
    public ResponseEntity<Void> eliminar(@PathVariable @NombreRecurso String nombre,
                                         @RequestParam(defaultValue = "false") boolean forzar) {
        service.eliminarCola(nombre, forzar);
        return ResponseEntity.noContent().build();
    }
}
