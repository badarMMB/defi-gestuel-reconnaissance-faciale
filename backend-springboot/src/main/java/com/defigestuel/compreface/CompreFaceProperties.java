package com.defigestuel.compreface;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * L'accès au service de reconnaissance CompreFace.
 *
 * @param url              adresse de CompreFace (l'interface, qui relaie l'API), par exemple http://localhost:8000
 * @param apiKey           clé du service de reconnaissance (type « Recognition »)
 * @param detProbThreshold probabilité minimale qu'une zone soit un visage
 * @param timeout          délai maximal d'une requête
 */
@ConfigurationProperties("compreface")
public record CompreFaceProperties(String url, String apiKey, double detProbThreshold, Duration timeout) {

    public CompreFaceProperties {
        timeout = timeout == null ? Duration.ofSeconds(20) : timeout;
        detProbThreshold = detProbThreshold <= 0 ? 0.8 : detProbThreshold;
    }

    public boolean configured() {
        return url != null && !url.isBlank() && apiKey != null && !apiKey.isBlank();
    }
}
