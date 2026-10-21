import { describe, expect, it } from 'vitest';
import { plain, PLAIN_VOICE } from '../src/ai/voice.js';

describe('plain voice', () => {
  it('turns em and en dashes into commas', () => {
    expect(plain('Easy run — 5 km at conversational pace')).toBe('Easy run, 5 km at conversational pace');
    expect(plain('Read 10 pages–then note one idea')).toBe('Read 10 pages, then note one idea');
    expect(plain('Stretch, — 10 min')).toBe('Stretch, 10 min');
  });
  it('leaves ordinary hyphens alone', () => {
    expect(plain('A 7-day streak')).toBe('A 7-day streak');
  });
  it('is part of the instructions', () => {
    expect(PLAIN_VOICE).toMatch(/no em dashes/);
  });
});
