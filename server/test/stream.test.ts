import { describe, expect, it } from 'vitest';
import { StreamJoiner } from '../src/ai/replicate.js';
import { routineLine } from '../src/coach/routes.js';

describe('routine summary for the coach', () => {
  it('says when the user is usually active, in plain words', () => {
    expect(routineLine({ days: 12, openHour: 21, doneHour: 20, finishedBy: 22, wifiShare: 0.8 })).toBe(
      "Their routine over the last 12 days (they allowed the app to learn it): usually opens the app around 9 PM, gets tasks done around 8 PM, is done by 10 PM on most days, mostly on Wi-Fi (home or work). Use it to time suggestions; don't mention that it is tracked unless they ask.");
    expect(routineLine({ days: 5, openHour: 0, finishedBy: 25, wifiShare: 0.2 })).toContain('around midnight, is done by 1 AM on most days, mostly on mobile data');
  });
});

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

describe('textInput', async () => {
  const { textInput } = await import('../src/ai/replicate.js');
  it('raises max_tokens to 1024 for Claude on Replicate, and leaves other models alone', () => {
    expect(textInput('anthropic/claude-4.5-haiku', { prompt: 'x', max_tokens: 3 })).toEqual({ prompt: 'x', max_tokens: 1024 });
    expect(textInput('anthropic/claude-opus-4.7', { prompt: 'x', max_tokens: 1500 })).toEqual({ prompt: 'x', max_tokens: 1500 });
    expect(textInput('meta/llama', { prompt: 'x', max_tokens: 3 })).toEqual({ prompt: 'x', max_tokens: 3 });
  });
});
