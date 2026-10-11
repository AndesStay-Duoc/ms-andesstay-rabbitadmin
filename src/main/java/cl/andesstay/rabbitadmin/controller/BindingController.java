package cl.andesstay.rabbitadmin.controller;

import cl.andesstay.rabbitadmin.service.RabbitAdminService;
import cl.andesstay.rabbitadmin.web.dto.BindingDtos;
import cl.andesstay.rabbitadmin.web.dto.BindingDtos.BindingResponse;
import cl.andesstay.rabbitadmin.web.dto.BindingDtos.CrearBindingRequest;
import cl.andesstay.rabbitadmin.web.dto.NombreRecurso;
import cl.andesstay.rabbitadmin.web.dto.Restricciones;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Bindings del vhost configurado, sin los implícitos del exchange por omisión.
 *
 *   GET    /api/v1/admin/bindings               200  lista
 *   POST   /api/v1/admin/bindings               201                 | 404 si falta el exchange o el destino | 409 si ya existe
 *   DELETE /api/v1/admin/bindings?exchange=&destino=&tipoDestino=&routingKey=
 *                                               204                 | 404 si no existe
 *
 * Un binding no tiene nombre propio: lo identifican sus cuatro datos. Por eso el DELETE los recibe
 * como parámetros de consulta en lugar de un {nombre} en la ruta, y no hay GET de detalle.
 *
 * En la consulta, una routing key con '#' debe ir codificada como %23: sin codificar, el navegador
 * y curl la toman como inicio del fragmento y no la envían.
 */
@RestController
@RequestMapping(BindingController.RUTA)
@RequiredArgsConstructor
public class BindingController {

    public static final String RUTA = "/api/v1/admin/bindings";

    private final RabbitAdminService service;

    @GetMapping
    public List<BindingResponse> listar() {
        return service.listarBindings().stream().map(BindingResponse::desde).toList();
    }

    /** Sin Location: el binding no tiene una URL de detalle a la que apuntar. */
    @PostMapping
    public ResponseEntity<BindingResponse> crear(@Valid @RequestBody CrearBindingRequest peticion) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(BindingResponse.desde(service.crearBinding(peticion.aDefinicion())));
    }

    @DeleteMapping
    public ResponseEntity<Void> eliminar(
            @RequestParam @NombreRecurso String exchange,
            @RequestParam @NombreRecurso String destino,
            @RequestParam(required = false)
            @Pattern(regexp = BindingDtos.TIPOS_DESTINO, message = BindingDtos.MENSAJE_TIPOS_DESTINO)
            String tipoDestino,
            @RequestParam(required = false)
            @Size(max = Restricciones.LARGO_MAXIMO, message = "admite como máximo " + Restricciones.LARGO_MAXIMO + " caracteres")
            @Pattern(regexp = Restricciones.ROUTING_KEY, message = Restricciones.MENSAJE_ROUTING_KEY)
            String routingKey) {
        service.eliminarBinding(BindingDtos.definicion(exchange, destino, tipoDestino, routingKey));
        return ResponseEntity.noContent().build();
    }
}
