import { describe, expect, it } from 'vitest';
import { StreamJoiner } from '../src/ai/replicate.js';

const join = (chunks: string[]) => { const j = new StreamJoiner(); return chunks.map(c => j.push(c)).join(''); };

describe('StreamJoiner', () => {
  it('passes an ordinary stream through unchanged', () => {
    expect(join(['one', ' two three', ' four'])).toBe('one two three four');
    expect(join(['Hi', '.', ' Hi', ' again'])).toBe('Hi. Hi again');
  });

  it('drops the restarts Claude on Replicate sends mid-stream (captured 2026-10-20)', () => {
    const full = '1. Bridges connect places separated by obstacles like rivers, valleys, or roads.\n2. They come in many designs, including suspension, arch, and beam styles.\n3. Some famous bridges, like the Golden Gate, have become iconic landmarks.';
    expect(join(['', '1. B', 'ridges connect places separated by obstacles like rivers, valleys, or roads.\n2. They come', ' in many designs, including',
      '1. Bridges conn', 'ect places separated by obstacles like rivers,', '1. Bridges connect places separ', 'ated by ob', 'stacles like rivers, val', 'leys, or ro',
      'ads.\n2. They', ' come in many design', '1. Bridges connect places separated by obstacles like rivers, valleys, or roads.\n2. They come in many',
      ' designs, including suspension, arch, and beam styles.\n3. Some famous bridges, like the Golden Gate, have become iconic landmarks.'])).toBe(full);
  });

  it('handles a restart that runs past what was already sent', () => {
    expect(join(['Start', ' absurdly small', 'Start absurdly small—so small it', ' feels silly.'])).toBe('Start absurdly small—so small it feels silly.');
  });

  it('carries on if a delta only looked like a restart', () => {
    // "The" is a prefix of the reply; after it the chunks don't continue a replay, so they are appended.
    expect(join(['The plan', ' works. ', 'The', 're you go'])).toBe('The plan works. There you go');
  });
});
