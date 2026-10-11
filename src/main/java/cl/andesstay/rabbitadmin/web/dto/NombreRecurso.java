package cl.andesstay.rabbitadmin.web.dto;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Nombre obligatorio de una cola o un exchange.
 *
 * RabbitMQ admite hasta 255 bytes de casi cualquier carácter, pero un nombre con espacios, barras o
 * tildes complica las URL de esta API y de la consola, y suele ser un error de tipeo. Se restringe al
 * conjunto que ya usa la topología del caso (q.cmd.email.dlq, cmd.dead.dlx).
 *
 * Los nombres "amq.*" pasan esta validación a propósito: no son inválidos sino reservados, y los
 * rechaza RabbitAdminService con 403 OPERACION_NO_PERMITIDA, que describe mejor el motivo.
 */
@NotBlank(message = "es obligatorio")
@Size(max = Restricciones.LARGO_MAXIMO, message = "admite como máximo " + Restricciones.LARGO_MAXIMO + " caracteres")
@Pattern(regexp = Restricciones.CARACTERES_DE_NOMBRE, message = Restricciones.MENSAJE_CARACTERES_DE_NOMBRE)
@Constraint(validatedBy = {})
@Documented
@Target({FIELD, METHOD, PARAMETER})
@Retention(RUNTIME)
public @interface NombreRecurso {

    String message() default "nombre inválido";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
