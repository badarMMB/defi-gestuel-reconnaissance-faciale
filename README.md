# Prototype : défi gestuel et reconnaissance faciale

Pour prouver qu'une personne est présente devant la caméra, on lui demande un code de 3 chiffres tiré au hasard. Elle
montre chaque chiffre avec les doigts de ses deux mains, le visage dans l'image. Chaque photo est reconnue par
CompreFace. Le défi est accepté si les 3 chiffres sont justes et si les 3 photos montrent la même personne, avec une
ressemblance d'au moins 85 %, le tout entre 2 et 45 secondes.

```
docker-compreface/    CompreFace 1.2.0 (reconnaissance faciale), docker compose
backend-springboot/   orchestrateur Java 21 / Spring Boot 3.5 : défis, appel à CompreFace, décision
frontend-angular/     Angular 22 : caméra, MediaPipe (visage + mains) dans le navigateur, étapes et verdict
```

## Démarrer

1. **CompreFace** : voir [docker-compreface/README.md](docker-compreface/README.md). Démarrez-le, créez un service
   **Recognition** et enregistrez les visages des personnes.
2. **Backend**, sur le port 8080 :
   ```bash
   cd backend-springboot
   COMPREFACE_URL=http://localhost:8000 COMPREFACE_API_KEY=<clé du service Recognition> mvn spring-boot:run
   ```
3. **Frontend**, sur le port 4200 :
   ```bash
   cd frontend-angular
   npm install
   npx ng serve
   ```
   Ouvrez <http://localhost:4200>. La caméra n'est permise qu'en https, ou sur `localhost`. Ajoutez `?delegate=CPU` à
   l'adresse si la carte graphique de l'appareil pose problème.

## Validation automatique

L'écran envoie la photo tout seul dès que le chiffre demandé est montré et tenu 0,7 seconde, avec un seul visage et
deux mains. Un comptage qui clignote ne déclenche rien. Si deux chiffres de suite sont identiques, il faut baisser les
mains entre les deux : une même pose ne valide jamais deux étapes. Une case permet de couper la validation automatique
et de valider avec le bouton.

## Distance à la caméra

Le visage est reconnu jusqu'à 2 ou 3 mètres d'une caméra 1280 × 720 ordinaire (un visage d'environ 32 pixels de
large). Au-delà, l'écran demande de se rapprocher.

- **Dans le navigateur**, le détecteur de visages de MediaPipe ne voit pas un visage plus petit qu'un septième de
  l'image : au-delà d'un mètre, il le perd. Le visage est donc aussi cherché dans des morceaux agrandis de l'image, puis
  confirmé dans un carré serré autour de lui, ce qui écarte les mains prises pour des visages. Détail dans
  `frontend-angular/src/app/vision/face-search.ts`.
- **Dans CompreFace**, le réglage `max_detect_size` doit valoir 1280 : à 640, la valeur par défaut, la photo est
  divisée par deux avant la recherche, et un visage lointain n'est plus trouvé.

## L'API

| Appel | Rôle |
|---|---|
| `POST /api/v1/challenges` | Crée un défi. Réponse : `{ challengeId, step: 1, targetDigit, expiresInSeconds: 45, totalSteps: 3 }` |
| `POST /api/v1/challenges/{id}/steps` | Formulaire multipart avec `step` (1 à 3), `detectedDigit` (0 à 9) et `image` (JPEG ou PNG, 5 Mo au plus). Réponse : l'étape et le chiffre suivants, ou le verdict `{ decision: APPROVED | REJECTED, subject, reasons[], steps[] }` |
| `GET /api/v1/challenges/{id}` | L'état d'un défi et son verdict, lisible jusqu'à 5 minutes après la fin |

Les erreurs sont au format `problem+json` :

| Code | Cas |
|---|---|
| 404 | défi inconnu |
| 409 | défi terminé, ou étape dans le désordre |
| 400 | requête mal formée |
| 413 | photo trop lourde |
| 503 | CompreFace indisponible : l'étape n'est pas comptée et peut être refaite |

Un mauvais chiffre, une photo sans visage ou avec plusieurs visages, ou un délai dépassé rejettent le défi : un code
ne sert qu'une fois.

## Tests

- Backend : `mvn verify`, 18 tests. Ils couvrent le parcours complet avec un faux CompreFace et une horloge réglable,
  et le client CompreFace contre un serveur HTTP local.
- Frontend : `npx ng test`, 13 tests du comptage des doigts. Huit portent sur des mains de synthèse (gauche, droite,
  de dos, inclinées), cinq sur des repères réels relevés par MediaPipe sur ses photos de test.

## Limites connues : ne pas utiliser tel quel pour autoriser des paiements

Les essais du 23 novembre 2026, avec le vrai CompreFace, ont montré trois faiblesses de conception :

1. **Le serveur croit le chiffre que lui envoie le navigateur.** Les doigts ne sont comptés que dans le navigateur. Un
   programme qui appelle l'API directement envoie une photo du visage et recopie le chiffre demandé. Défi accepté en
   3,4 secondes, sans aucune main dans l'image.
2. **Les mains ne prouvent pas que le visage est vivant.** Une photo fixe du visage, avec des mains montrées à côté,
   a été acceptée à 99,9 %. Un fraudeur peut tenir la photo du bénéficiaire et montrer les chiffres avec ses propres
   mains.
3. **La reconnaissance se fait parmi toute la collection.** Le défi dit qui est devant la caméra, pas si c'est le
   payeur attendu. Pour un paiement, il faut comparer à la seule photo de référence du payeur.
