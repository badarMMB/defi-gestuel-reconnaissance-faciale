# CompreFace pour le défi gestuel

CompreFace 1.2.0 (Exadel, licence Apache 2.0) reconnaît les visages. Le prototype n'utilise que son API REST de
**reconnaissance** : « qui, parmi les personnes enregistrées, est sur cette photo, et avec quelle ressemblance ? ».

## 1. Démarrer

```bash
cd docker-compreface
cp .env.example .env          # puis remplacez postgres_password par un vrai mot de passe
docker compose up -d
docker compose ps             # attendre que defi-compreface-core soit « healthy » (1 à 2 minutes)
```

L'interface est sur <http://localhost:8000>, accessible seulement depuis la machine elle-même. Pour arrêter :
`docker compose down`. Les données restent dans le volume `postgres-data`. `docker compose down -v` les efface.

Il faut 4 Go de mémoire libre et un processeur qui gère les instructions AVX : c'est le cas de presque tous les PC
récents.

## 2. Créer le service de reconnaissance et récupérer sa clé

1. Ouvrez <http://localhost:8000> et créez le compte propriétaire, avec un e-mail et un mot de passe.
2. **Create application**, par exemple « Défi gestuel ».
3. Dans l'application, **Add service**, type **Recognition** (pas *Verification* ni *Detection*), nom « Défi gestuel ».
4. Copiez la clé du service, la colonne *API key*, un UUID. Elle se passe au backend par une variable d'environnement,
   jamais dans un fichier versionné :

```bash
export COMPREFACE_URL=http://localhost:8000
export COMPREFACE_API_KEY=<la clé du service Recognition>
```

## 3. Enregistrer les visages de la collection

Chaque personne est un *subject* : son nom, par exemple son numéro de bénéficiaire, et une ou plusieurs photos de
référence. Le backend renvoie ce nom quand il la reconnaît.

**Par l'interface :** dans le service, **Manage collection** → **Add subject**, puis glissez une ou plusieurs photos.

**Par l'API :**

```bash
# une photo de référence pour la personne « alice »
curl -X POST "http://localhost:8000/api/v1/recognition/faces?subject=alice&det_prob_threshold=0.8" \
     -H "x-api-key: $COMPREFACE_API_KEY" -F file=@alice.jpg

# la liste des personnes enregistrées
curl -H "x-api-key: $COMPREFACE_API_KEY" http://localhost:8000/api/v1/recognition/subjects

# essayer une reconnaissance
curl -X POST "http://localhost:8000/api/v1/recognition/recognize?limit=0&prediction_count=1" \
     -H "x-api-key: $COMPREFACE_API_KEY" -F file=@photo.jpg
```

Conseils pour les photos de référence :
- le visage de face, net, bien éclairé, et qui occupe une bonne partie de l'image ;
- une photo du corps entier réduite à 480 pixels laisse un visage minuscule, et la ressemblance tombe à 70 % pour la
  bonne personne ;
- deux ou trois photos par personne, prises à des moments différents, rendent la reconnaissance plus stable.

## 4. Ce que CompreFace garde

- `save_images_to_db=false` (réglé dans `.env.example`) : CompreFace ne garde que l'empreinte numérique de chaque
  visage, pas les photos de référence elles-mêmes. Mettez `true` seulement si vous voulez revoir les photos dans
  l'interface.
- Les photos envoyées pour reconnaissance ne sont jamais gardées.
- Les empreintes de visages sont des données biométriques. La base PostgreSQL de CompreFace doit être protégée et
  sauvegardée comme toute donnée personnelle sensible.

## Utiliser une instance CompreFace déjà installée

Si une instance tourne déjà, par exemple sur le port 8001, inutile d'en démarrer une seconde. Créez-y un service
**Recognition** et pointez le backend dessus : `COMPREFACE_URL=http://localhost:8001`.

Vérifiez alors son réglage `max_detect_size` dans son fichier `.env` : à 640, la valeur par défaut, les visages à
plus d'un mètre et demi de la caméra ne sont pas trouvés. Passez-le à 1280, puis redémarrez seulement le moteur :
`docker compose up -d --no-deps compreface-core`.
