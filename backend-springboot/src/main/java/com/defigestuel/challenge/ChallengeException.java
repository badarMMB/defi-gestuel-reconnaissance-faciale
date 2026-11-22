package com.defigestuel.challenge;

import java.util.UUID;

/** Les refus qui ne terminent pas un défi : défi inconnu, déjà terminé, étape dans le désordre, photo illisible. */
public abstract sealed class ChallengeException extends RuntimeException {

    ChallengeException(String message) {
        super(message);
    }

    public static final class NotFound extends ChallengeException {
        NotFound(UUID id) {
            super("Défi inconnu ou oublié : " + id);
        }
    }

    public static final class Conflict extends ChallengeException {
        Conflict(String message) {
            super(message);
        }
    }

    public static final class InvalidImage extends ChallengeException {
        InvalidImage(String message) {
            super(message);
        }
    }

    public static final class TooMany extends ChallengeException {
        TooMany() {
            super("Trop de défis en cours : réessayez dans un instant");
        }
    }
}
