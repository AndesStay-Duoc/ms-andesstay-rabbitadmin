package cl.andesstay.rabbitadmin.web;

import cl.andesstay.rabbitadmin.service.OperacionNoPermitidaException;
import cl.andesstay.rabbitadmin.service.RecursoEnConflictoException;
import cl.andesstay.rabbitadmin.service.RecursoNoEncontradoException;
import cl.andesstay.rabbitadmin.web.CuerpoError.ErrorDeCampo;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.AmqpException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Traduce las excepciones a códigos HTTP y a un CuerpoError.
 *
 *   RecursoNoEncontradoException    404 RECURSO_NO_ENCONTRADO
 *   RecursoEnConflictoException     409 RECURSO_EN_CONFLICTO
 *   OperacionNoPermitidaException   403 OPERACION_NO_PERMITIDA   (nombres reservados amq.*)
 *   validación de entrada           400 SOLICITUD_INVALIDA       (con "campos": un error por campo)
 *   IllegalArgumentException        400 SOLICITUD_INVALIDA
 *   broker o API de gestión caídos  503 BROKER_NO_DISPONIBLE
 *   el broker rechazó la operación  502 BROKER_RECHAZO
 *
 * Deliberadamente NO se captura Exception a secas: un fallo inesperado tiene que seguir siendo un
 * 500 con su traza en el log, o se esconderían defectos reales. Es el mismo criterio de audit y
 * report.
 */
@Slf4j
@RestControllerAdvice
public class ManejadorDeErrores {

    @ExceptionHandler(RecursoNoEncontradoException.class)
    public ResponseEntity<CuerpoError> noEncontrado(RecursoNoEncontradoException e) {
        return responder(HttpStatus.NOT_FOUND, "RECURSO_NO_ENCONTRADO", e.getMessage());
    }

    @ExceptionHandler(RecursoEnConflictoException.class)
    public ResponseEntity<CuerpoError> enConflicto(RecursoEnConflictoException e) {
        return responder(HttpStatus.CONFLICT, "RECURSO_EN_CONFLICTO", e.getMessage());
    }

    @ExceptionHandler(OperacionNoPermitidaException.class)
    public ResponseEntity<CuerpoError> noPermitida(OperacionNoPermitidaException e) {
        return responder(HttpStatus.FORBIDDEN, "OPERACION_NO_PERMITIDA", e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<CuerpoError> solicitudInvalida(IllegalArgumentException e) {
        return responder(HttpStatus.BAD_REQUEST, "SOLICITUD_INVALIDA", e.getMessage());
    }

    // ── Validación de entrada: siempre 400 SOLICITUD_INVALIDA con el detalle por campo ──────

    /** Un @Valid @RequestBody con anotaciones que no se cumplen. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<CuerpoError> cuerpoInvalido(MethodArgumentNotValidException e) {
        List<ErrorDeCampo> campos = new ArrayList<>();
        e.getBindingResult().getFieldErrors().forEach(f -> campos.add(new ErrorDeCampo(f.getField(), f.getDefaultMessage())));
        e.getBindingResult().getGlobalErrors().forEach(g -> campos.add(new ErrorDeCampo(g.getObjectName(), g.getDefaultMessage())));
        return validacion(campos);
    }

    /**
     * Restricciones sobre parámetros de ruta o de consulta ({nombre}, exchange, routingKey...).
     * Cuando un método valida parámetros, Spring 6.1 valida también su @Valid @RequestBody por esta
     * misma vía, así que aquí llegan además los errores del cuerpo de los PUT.
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<CuerpoError> parametrosInvalidos(HandlerMethodValidationException e) {
        List<ErrorDeCampo> campos = new ArrayList<>();
        for (ParameterValidationResult resultado : e.getAllValidationResults()) {
            if (resultado instanceof ParameterErrors errores) {
                errores.getFieldErrors().forEach(f -> campos.add(new ErrorDeCampo(f.getField(), f.getDefaultMessage())));
                errores.getGlobalErrors().forEach(g -> campos.add(new ErrorDeCampo(g.getObjectName(), g.getDefaultMessage())));
            } else {
                String parametro = resultado.getMethodParameter().getParameterName();
                resultado.getResolvableErrors().forEach(r -> campos.add(new ErrorDeCampo(parametro, r.getDefaultMessage())));
            }
        }
        return validacion(campos);
    }

    /**
     * Cuerpo que no se pudo convertir: JSON mal formado, un tipo incorrecto ("ttlMensajesMs":"abc")
     * o un campo desconocido, que se rechaza por spring.jackson.deserialization.fail-on-unknown-properties.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<CuerpoError> cuerpoIlegible(HttpMessageNotReadableException e) {
        Throwable causa = e.getMostSpecificCause();
        if (causa instanceof UnrecognizedPropertyException desconocido) {
            return validacion(List.of(new ErrorDeCampo(desconocido.getPropertyName(),
                    "campo desconocido; los válidos son " + desconocido.getKnownPropertyIds())));
        }
        if (causa instanceof JsonMappingException mapeo && !mapeo.getPath().isEmpty()) {
            String campo = mapeo.getPath().stream()
                    .map(r -> r.getFieldName() != null ? r.getFieldName() : "[" + r.getIndex() + "]")
                    .collect(Collectors.joining("."));
            return validacion(List.of(new ErrorDeCampo(campo, "tiene un valor de tipo incorrecto")));
        }
        return responder(HttpStatus.BAD_REQUEST, "SOLICITUD_INVALIDA",
                "El cuerpo de la petición falta o no es un JSON válido");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<CuerpoError> parametroFaltante(MissingServletRequestParameterException e) {
        return validacion(List.of(new ErrorDeCampo(e.getParameterName(), "es obligatorio")));
    }

    /** Por ejemplo ?forzar=quizas. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<CuerpoError> parametroDeTipoIncorrecto(MethodArgumentTypeMismatchException e) {
        String esperado = e.getRequiredType() == null ? "otro tipo" : e.getRequiredType().getSimpleName();
        return validacion(List.of(new ErrorDeCampo(e.getName(),
                "el valor '%s' no es válido; se esperaba %s".formatted(e.getValue(), esperado))));
    }

    private static ResponseEntity<CuerpoError> validacion(List<ErrorDeCampo> campos) {
        String mensaje = campos.size() == 1
                ? "La solicitud tiene un error de validación"
                : "La solicitud tiene %d errores de validación".formatted(campos.size());
        return ResponseEntity.badRequest().body(CuerpoError.deValidacion(mensaje, campos));
    }

    /** Sin conexión AMQP no hay nada que administrar; no es un defecto de este servicio. */
    @ExceptionHandler(AmqpConnectException.class)
    public ResponseEntity<CuerpoError> brokerCaido(AmqpConnectException e) {
        log.error("[Admin] Broker inalcanzable por AMQP: {}", e.getMessage());
        return responder(HttpStatus.SERVICE_UNAVAILABLE, "BROKER_NO_DISPONIBLE",
                "No se pudo conectar con RabbitMQ por AMQP");
    }

    /** Conexión rechazada o timeout contra la API de gestión. */
    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<CuerpoError> gestionCaida(ResourceAccessException e) {
        log.error("[Admin] API de gestión inalcanzable: {}", e.getMessage());
        return responder(HttpStatus.SERVICE_UNAVAILABLE, "BROKER_NO_DISPONIBLE",
                "No se pudo conectar con la API de gestión de RabbitMQ");
    }

    /**
     * El broker respondió pero rechazó la operación: un PRECONDITION_FAILED, un ACCESS_REFUSED por
     * permisos del usuario, o credenciales inválidas en la API de gestión. Se responde 502 porque
     * el fallo está aguas abajo, y se incluye el motivo, que es lo que permite corregirlo.
     */
    @ExceptionHandler({AmqpException.class, RestClientException.class})
    public ResponseEntity<CuerpoError> brokerRechazo(RuntimeException e) {
        log.error("[Admin] RabbitMQ rechazó la operación", e);
        return responder(HttpStatus.BAD_GATEWAY, "BROKER_RECHAZO",
                "RabbitMQ rechazó la operación: " + e.getMessage());
    }

    private static ResponseEntity<CuerpoError> responder(HttpStatus status, String codigo, String mensaje) {
        return ResponseEntity.status(status).body(CuerpoError.de(codigo, mensaje));
    }
}
