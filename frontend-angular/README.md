# Frontend du défi gestuel

Angular 22. La caméra du navigateur (`getUserMedia`) est analysée sur place par MediaPipe
(`@mediapipe/tasks-vision` 1.2.2, Apache 2.0) :
- `FaceDetector` trouve les visages ;
- `HandLandmarker` donne les 21 repères de chaque main.

Le code MediaPipe (`node_modules/@mediapipe/tasks-vision/wasm`, publié sous `/mediapipe-wasm`) et les deux modèles
(`public/models/`) sont servis par l'application elle-même : la page ne charge rien depuis un serveur tiers.

```bash
npm install
npx ng serve          # http://localhost:4200 ; ajoutez ?delegate=CPU si la carte graphique pose problème
npx ng test           # tests du comptage des doigts
npx ng build          # version de production dans dist/
```

- `src/app/vision/finger-count.ts` : le comptage des doigts, de 0 à 9 sur deux mains, une gauche et une droite. Les
  tests se font dans le repère de la main, donc valent pour les deux mains, paume ou dos, inclinées.
- `src/app/vision/vision.ts` : le chargement de MediaPipe, sur le processeur graphique avec repli sur le processeur
  central, et l'analyse d'une image.
- `src/app/challenge/` : l'écran du défi et l'appel à l'API. L'adresse du backend est dans `src/app/config.ts`.

Modèles : `face_detector.tflite` (BlazeFace courte portée) et `hand_landmarker.task`, téléchargés depuis
`storage.googleapis.com/mediapipe-models`.
