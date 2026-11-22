package com.defigestuel.compreface;

/** Le service de reconnaissance ne répond pas, ou refuse la requête (clé fausse, service arrêté). */
public class FaceServiceException extends RuntimeException {

    public FaceServiceException(String message) {
        super(message);
    }

    public FaceServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
