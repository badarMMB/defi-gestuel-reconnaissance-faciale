/**
 * L'adresse du backend Spring Boot. Le backend autorise l'origine http://localhost:4200 (CORS) ; pour une autre
 * adresse, changez celle-ci et la propriété challenge.allowed-origins du backend.
 */
export const API_URL = 'http://localhost:8080';

/** Les fichiers de MediaPipe, servis par l'application elle-même : rien n'est chargé d'un serveur tiers. */
export const MEDIAPIPE_WASM = '/mediapipe-wasm';
export const FACE_MODEL = '/models/face_detector.tflite';
export const HAND_MODEL = '/models/hand_landmarker.task';
