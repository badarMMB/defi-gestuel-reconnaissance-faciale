package com.defigestuel.challenge;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Les défis en mémoire. Un défi en cours est gardé jusqu'à son expiration, puis, comme un défi terminé, pendant la
 * durée de rétention, pour qu'on puisse encore lire son verdict. Le nombre de défis est plafonné : un client qui en
 * créerait sans fin ne peut pas épuiser la mémoire du serveur.
 */
@Component
public class ChallengeStore {

    static final int MAX_CHALLENGES = 10_000;
    private static final Logger LOG = LoggerFactory.getLogger(ChallengeStore.class);

    private final Map<UUID, Challenge> challenges = new ConcurrentHashMap<>();
    private final ChallengeProperties properties;
    private final Clock clock;

    public ChallengeStore(ChallengeProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    Challenge add(Challenge challenge) {
        if (challenges.size() >= MAX_CHALLENGES) {
            removeStale();
            if (challenges.size() >= MAX_CHALLENGES) {
                throw new ChallengeException.TooMany();
            }
        }
        challenges.put(challenge.id(), challenge);
        return challenge;
    }

    Optional<Challenge> find(UUID id) {
        return Optional.ofNullable(challenges.get(id));
    }

    int size() {
        return challenges.size();
    }

    @Scheduled(fixedDelayString = "${challenge.cleanup-interval:30s}", initialDelayString = "${challenge.cleanup-interval:30s}")
    public void removeStale() {
        Instant limit = clock.instant().minus(properties.retention());
        int before = challenges.size();
        challenges.values().removeIf(c -> {
            synchronized (c) {
                Instant end = c.finished() ? c.finishedAt() : c.expiresAt();
                return end.isBefore(limit);
            }
        });
        int removed = before - challenges.size();
        if (removed > 0) {
            LOG.debug("{} défi(s) oublié(s), {} en mémoire", removed, challenges.size());
        }
    }
}
