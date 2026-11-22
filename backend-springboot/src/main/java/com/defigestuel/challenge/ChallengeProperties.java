package com.defigestuel.challenge;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Les règles du défi.
 *
 * @param ttl            durée de vie d'un défi, des 3 étapes comprises
 * @param minDuration    durée minimale entre la création du défi et la dernière photo
 * @param minSimilarity  ressemblance minimale exigée sur chaque photo (0 à 1)
 * @param retention      durée pendant laquelle un défi terminé ou expiré reste en mémoire
 * @param cleanupInterval fréquence du nettoyage
 * @param allowedOrigins origines web autorisées à appeler l'API (CORS)
 */
@ConfigurationProperties("challenge")
public record ChallengeProperties(Duration ttl, Duration minDuration, double minSimilarity, Duration retention,
        Duration cleanupInterval, List<String> allowedOrigins) {

    public ChallengeProperties {
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("challenge.ttl doit être positif");
        }
        if (minDuration == null || minDuration.isNegative() || minDuration.compareTo(ttl) >= 0) {
            throw new IllegalArgumentException("challenge.min-duration doit être compris entre 0 et challenge.ttl");
        }
        if (minSimilarity <= 0 || minSimilarity > 1) {
            throw new IllegalArgumentException("challenge.min-similarity doit être compris entre 0 et 1");
        }
        retention = retention == null ? Duration.ofMinutes(5) : retention;
        cleanupInterval = cleanupInterval == null ? Duration.ofSeconds(30) : cleanupInterval;
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    }
}
