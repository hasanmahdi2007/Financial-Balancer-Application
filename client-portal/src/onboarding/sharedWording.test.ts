import { describe, expect, it } from 'vitest';
import { liftSharedEnding } from './sharedWording';
import questions from '../test/fixtures/manual-form-LB.json';

describe('wording every question repeats', () => {
  it('is lifted out once, leaving each question its own sentence', () => {
    const { shared, particular } = liftSharedEnding([
      'We ask because rent is usually the largest bill. Prices are in US dollars.',
      'We ask because food is a weekly cost. Prices are in US dollars.',
    ]);

    expect(shared).toBe('Prices are in US dollars.');
    expect(particular).toEqual([
      'We ask because rent is usually the largest bill.',
      'We ask because food is a weekly cost.',
    ]);
  });

  it('lifts the whole sentence when every question says nothing else', () => {
    const identical = ['A rough figure for Lebanon.', 'A rough figure for Lebanon.'];

    expect(liftSharedEnding(identical)).toEqual({ shared: 'A rough figure for Lebanon.', particular: ['', ''] });
  });

  it('leaves questions alone when they share no ending', () => {
    const different = ['Rent is the largest bill.', 'Food is a weekly cost.'];

    expect(liftSharedEnding(different)).toEqual({ shared: null, particular: different });
  });

  it('says the real form’s caveat once instead of ten times', () => {
    // Against the recorded payload, where all ten questions carry word-for-word the same reason and
    // the same note about Lebanese prices being quoted in dollars.
    const { shared, particular } = liftSharedEnding(questions.map((q) => q.why));

    expect(shared).toContain('US dollars');
    expect(particular).toEqual(questions.map(() => ''));
  });
});
