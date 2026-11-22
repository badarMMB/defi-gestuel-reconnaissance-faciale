package com.defigestuel.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import com.defigestuel.challenge.ChallengeException;
import com.defigestuel.compreface.FaceServiceException;

/** Les erreurs de l'API, au format « problem+json » (RFC 9457), avec un message lisible dans {@code detail}. */
@RestControllerAdvice
public class ApiErrors {

    private static final Logger LOG = LoggerFactory.getLogger(ApiErrors.class);

    @ExceptionHandler(ChallengeException.class)
    ProblemDetail challenge(ChallengeException e) {
        HttpStatus status = switch (e) {
            case ChallengeException.NotFound ignored -> HttpStatus.NOT_FOUND;
            case ChallengeException.Conflict ignored -> HttpStatus.CONFLICT;
            case ChallengeException.InvalidImage ignored -> HttpStatus.BAD_REQUEST;
            case ChallengeException.TooMany ignored -> HttpStatus.TOO_MANY_REQUESTS;
        };
        return ProblemDetail.forStatusAndDetail(status, e.getMessage());
    }

    @ExceptionHandler(FaceServiceException.class)
    ProblemDetail faceService(FaceServiceException e) {
        LOG.warn("Reconnaissance faciale indisponible : {}", e.getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "La reconnaissance faciale est indisponible : l'étape n'est pas comptée, réessayez");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ProblemDetail tooLarge(MaxUploadSizeExceededException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE, "La photo dépasse 5 Mo");
    }

    @ExceptionHandler({ MissingServletRequestParameterException.class, MissingServletRequestPartException.class,
            MethodArgumentTypeMismatchException.class })
    ProblemDetail badRequest(Exception e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Requête incomplète ou mal formée : " + e.getMessage());
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ProblemDetail invalid(HandlerMethodValidationException e) {
        String detail = e.getParameterValidationResults().stream()
                .map(r -> r.getMethodParameter().getParameterName() + " : "
                        + r.getResolvableErrors().stream().map(err -> err.getDefaultMessage()).toList())
                .reduce((a, b) -> a + " ; " + b).orElse("valeur invalide");
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
    }
}
