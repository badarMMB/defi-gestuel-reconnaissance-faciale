import { Box, CONFIRM_SCORE, confirmRegion, confirmed, fromRegion, mergeFaces, scanRegions } from './face-search';

const box = (x: number, y: number, size: number, score = 0.9): Box => ({ x, y, width: size, height: size, score });

describe('scanRegions', () => {
  const levels = scanRegions(1280, 720);

  it('couvre toute l\'image à chaque taille de carré : hauteur, moitié, tiers', () => {
    expect(levels.map((level) => level[0].size)).toEqual([720, 360, 240]);
    for (const level of levels) {
      expect(Math.min(...level.map((r) => r.x))).toBe(0);
      expect(Math.max(...level.map((r) => r.x + r.size))).toBe(1280);
      expect(Math.min(...level.map((r) => r.y))).toBe(0);
      expect(Math.max(...level.map((r) => r.y + r.size))).toBe(720);
      level.forEach((r) => {
        expect(r.x + r.size).toBeLessThanOrEqual(1280);
        expect(r.y + r.size).toBeLessThanOrEqual(720);
      });
    }
  });

  it('place tout visage entier dans au moins un carré de la taille qui sait le voir', () => {
    // Un carré voit un visage d'au moins un septième de sa taille ; au-dessus de 0,3, le carré plus grand le voit.
    levels.forEach((level) => {
      const face = Math.round(level[0].size * 0.3);
      for (let x = 0; x <= 1280 - face; x += 37) {
        for (let y = 0; y <= 720 - face; y += 29) {
          const inside = level.some((r) => r.x <= x && r.y <= y && r.x + r.size >= x + face && r.y + r.size >= y + face);
          expect(inside).toBe(true);
        }
      }
    });
  });
});

describe('confirmRegion', () => {
  it('centre un carré de quatre fois la taille du visage, sans sortir de l\'image', () => {
    expect(confirmRegion(box(600, 300, 100), 1280, 720)).toEqual({ x: 450, y: 150, size: 400 });
    expect(confirmRegion(box(5, 5, 100), 1280, 720)).toEqual({ x: 0, y: 0, size: 400 });
    const big = confirmRegion(box(400, 100, 300), 1280, 720);
    expect(big.size).toBe(720);
    expect(big.y).toBe(0);
    expect(confirmRegion(box(600, 300, 10), 1280, 720).size).toBe(48);
    expect(confirmRegion(box(600, 300, 40), 1280, 720).size).toBe(160);
  });
});

describe('confirmed', () => {
  const candidate = box(600, 300, 60, 0.65);

  it('garde la détection la plus sûre qui recouvre le candidat', () => {
    expect(confirmed(candidate, [box(602, 298, 58, 0.85), box(604, 302, 57, 0.97)])?.score).toBe(0.97);
  });

  it('refuse un candidat que le carré serré ne confirme pas, ou trop peu sûrement', () => {
    expect(confirmed(candidate, [])).toBeNull();
    expect(confirmed(candidate, [box(602, 298, 58, CONFIRM_SCORE - 0.01)])).toBeNull();
    expect(confirmed(candidate, [box(800, 300, 60, 0.99)])).toBeNull();
  });
});

describe('fromRegion', () => {
  it('ramène les coordonnées dans l\'image', () => {
    const [b] = fromRegion([box(96, 48, 32)], { x: 400, y: 200, size: 360 }, 192, 1280, 720);
    expect(b.x).toBeCloseTo(580);
    expect(b.y).toBeCloseTo(290);
    expect(b.width).toBeCloseTo(60);
  });

  it('écarte un visage coupé par un bord intérieur, garde celui qui touche le bord de l\'image', () => {
    const region = { x: 400, y: 0, size: 360 };
    expect(fromRegion([box(0, 50, 40)], region, 192, 1280, 720)).toEqual([]);
    expect(fromRegion([box(160, 50, 32)], region, 192, 1280, 720)).toEqual([]);
    expect(fromRegion([box(50, 0, 40)], region, 192, 1280, 720)).toHaveLength(1);
  });
});

describe('mergeFaces', () => {
  it('compte une fois un visage vu dans plusieurs carrés, et deux visages distincts deux fois', () => {
    const merged = mergeFaces([box(100, 100, 60, 0.7), box(104, 98, 58, 0.95), box(500, 100, 60, 0.8)]);
    expect(merged).toHaveLength(2);
    expect(merged[0].score).toBe(0.95);
  });

  it('confond un petit cadre posé dans un grand', () => {
    expect(mergeFaces([box(100, 100, 120), box(130, 130, 50, 0.6)])).toHaveLength(1);
  });
});
