package com.defigestuel.compreface;

/** Reconnaître la personne présente sur une photo, parmi celles enregistrées dans la collection de visages. */
public interface FaceRecognizer {

    /**
     * @param image le contenu de la photo (JPEG ou PNG)
     * @return le nombre de visages trouvés, et la personne la plus ressemblante pour le visage principal
     * @throws FaceServiceException si le service de reconnaissance ne répond pas ou refuse la requête
     */
    Recognition recognize(byte[] image, String contentType);

    /**
     * Le résultat d'une reconnaissance.
     *
     * @param faceCount  nombre de visages trouvés sur la photo
     * @param subject    la personne la plus ressemblante (vide si aucun visage, ou si la collection est vide)
     * @param similarity sa ressemblance, de 0 à 1 (vide avec le sujet)
     */
    record Recognition(int faceCount, String subject, Double similarity) {

        public static Recognition noFace() {
            return new Recognition(0, null, null);
        }
    }
}
