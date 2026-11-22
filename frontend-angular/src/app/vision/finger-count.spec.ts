import { Point, readDigit, readHand } from './finger-count';
import realHands from './real-hands.fixture.json';

/**
 * Des mains synthétiques, en coordonnées d'image (x vers la droite, y vers le bas, de 0 à 1) : une main droite, paume
 * vers la caméra, doigts vers le haut. Chaque doigt est levé ou plié ; le pouce est levé, plié sur la paume, ou collé
 * contre l'index.
 */
type Thumb = 'up' | 'across' | 'tucked';

function hand(thumb: Thumb, fingers: [boolean, boolean, boolean, boolean]): Point[] {
  const p: Point[] = new Array(21);
  p[0] = { x: 0.5, y: 0.9 };
  p[1] = { x: 0.4, y: 0.82 };
  p[2] = { x: 0.35, y: 0.75 };
  if (thumb === 'up') {
    p[3] = { x: 0.29, y: 0.7 };
    p[4] = { x: 0.24, y: 0.66 };
  } else if (thumb === 'across') {
    p[3] = { x: 0.4, y: 0.72 };
    p[4] = { x: 0.48, y: 0.7 };
  } else {
    p[3] = { x: 0.38, y: 0.68 };
    p[4] = { x: 0.4, y: 0.62 };
  }
  const bases = [0.42, 0.48, 0.54, 0.6];
  fingers.forEach((up, i) => {
    const x = bases[i];
    const mcp = 5 + i * 4;
    p[mcp] = { x, y: 0.6 };
    if (up) {
      p[mcp + 1] = { x, y: 0.5 };
      p[mcp + 2] = { x, y: 0.43 };
      p[mcp + 3] = { x, y: 0.37 };
    } else {
      p[mcp + 1] = { x, y: 0.52 };
      p[mcp + 2] = { x, y: 0.57 };
      p[mcp + 3] = { x, y: 0.63 };
    }
  });
  return p;
}

/** La même main vue en miroir : une main gauche, ou une main droite vue de dos. */
function mirror(points: Point[]): Point[] {
  return points.map((q) => ({ x: 1 - q.x, y: q.y }));
}

/** La main tournée d'un angle (en degrés) autour du poignet. */
function rotate(points: Point[], degrees: number): Point[] {
  const r = (degrees * Math.PI) / 180;
  const c = points[0];
  return points.map((q) => ({
    x: c.x + (q.x - c.x) * Math.cos(r) - (q.y - c.y) * Math.sin(r),
    y: c.y + (q.x - c.x) * Math.sin(r) + (q.y - c.y) * Math.cos(r),
  }));
}

const OPEN = hand('up', [true, true, true, true]);
const FIST = hand('across', [false, false, false, false]);

describe('readHand', () => {
  it('compte 5 doigts sur une main ouverte et 0 sur un poing', () => {
    expect(readHand(OPEN, 'Right').count).toBe(5);
    expect(readHand(FIST, 'Right').count).toBe(0);
  });

  it('reconnaît chaque doigt séparément', () => {
    expect(readHand(hand('across', [true, false, false, false]), 'Right').fingers).toEqual([false, true, false, false, false]);
    expect(readHand(hand('across', [true, true, false, false]), 'Right').count).toBe(2);
    expect(readHand(hand('up', [false, false, false, false]), 'Right').fingers).toEqual([true, false, false, false, false]);
    expect(readHand(hand('across', [false, false, false, true]), 'Right').fingers).toEqual([false, false, false, false, true]);
  });

  it('ne compte pas un pouce plié sur la paume ni collé contre l\'index', () => {
    expect(readHand(hand('across', [true, true, true, true]), 'Right').count).toBe(4);
    expect(readHand(hand('tucked', [true, true, true, true]), 'Right').count).toBe(4);
  });

  it('compte de la même façon une main gauche, une main vue de dos et une main inclinée', () => {
    const three = hand('up', [true, true, false, false]);
    expect(readHand(mirror(three), 'Left').count).toBe(3);
    for (const degrees of [-60, -30, 30, 60, 90]) {
      expect(readHand(rotate(three, degrees), 'Right').count).toBe(3);
      expect(readHand(rotate(mirror(OPEN), degrees), 'Left').count).toBe(5);
      expect(readHand(rotate(FIST, degrees), 'Right').count).toBe(0);
    }
  });

  it('refuse une main incomplète', () => {
    expect(() => readHand(OPEN.slice(0, 20), 'Right')).toThrow();
  });
});

describe('readDigit', () => {
  const right = (points: Point[]) => ({ landmarks: points, side: 'Right' });
  const left = (points: Point[]) => ({ landmarks: mirror(points), side: 'Left' });

  it('additionne les doigts des deux mains, de 0 à 9', () => {
    expect(readDigit([left(FIST), right(FIST)]).digit).toBe(0);
    expect(readDigit([left(OPEN), right(hand('across', [true, true, false, false]))]).digit).toBe(7);
    expect(readDigit([left(OPEN), right(hand('up', [true, true, true, false]))]).digit).toBe(9);
  });

  it('refuse dix doigts, une seule main, trois mains, ou deux mains du même côté', () => {
    expect(readDigit([left(OPEN), right(OPEN)])).toEqual(expect.objectContaining({ digit: null, problem: expect.stringContaining('10 doigts') }));
    expect(readDigit([right(OPEN)]).problem).toBe('Montrez vos deux mains');
    expect(readDigit([left(FIST), right(FIST), right(FIST)]).problem).toBe('Trop de mains dans l\'image');
    expect(readDigit([right(FIST), right(OPEN)]).problem).toBe('Il faut une main gauche et une main droite');
  });

  it('accepte deux mains dont la latéralité est inconnue', () => {
    expect(readDigit([{ landmarks: FIST, side: null }, { landmarks: OPEN, side: undefined }]).digit).toBe(5);
  });
});

/**
 * Des repères réels, relevés par MediaPipe HandLandmarker sur les photos de test de MediaPipe (poing, index levé,
 * « V », pouce levé, main ouverte), affichées retournées devant une fausse caméra.
 */
describe('readHand sur des mains réelles', () => {
  for (const hand of realHands as { photo: string; expected: number; side: string; landmarks: Point[] }[]) {
    it(`compte ${hand.expected} doigt(s) sur la photo « ${hand.photo} »`, () => {
      expect(readHand(hand.landmarks, hand.side).count).toBe(hand.expected);
    });
  }
});
