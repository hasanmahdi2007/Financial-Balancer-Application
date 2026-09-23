import { describe, expect, it } from 'vitest';
import type { OpenQuestion } from '../api/plan';
import { groupByTarget, isChoice, parseTarget } from './answers';
import questions from '../test/fixtures/questions-new.json';

const open: OpenQuestion[] = questions;
const byKey = (key: string) => open.find((q) => q.key === key)!;

describe('working out where an answer goes', () => {
  it('reads every instruction the server actually sends', () => {
    // The fixture is a real /api/v1/questions response, so this fails if the server changes the
    // shape of `answerWith` - which is the only thing standing between an answer and the right field.
    for (const question of open) {
      const target = parseTarget(question.answerWith);
      expect(target, question.answerWith).not.toBeNull();
      expect(target!.method).toBe('PUT');
      expect(target!.path).toMatch(/^\/api\/v1\//);
      expect(target!.field).toBeTruthy();
    }
  });

  it('ignores an instruction it cannot read rather than guessing a field', () => {
    expect(parseTarget('answer this one somehow')).toBeNull();
    expect(parseTarget('')).toBeNull();
  });

  it('sends every category of spending in one body, because the server replaces the whole map', () => {
    // The trap this exists to avoid: `PUT /api/v1/spending {"groceries": ...}` does not merge into
    // what was sent before, it replaces it. One request per category would leave the user with only
    // the last category they answered, and the plan would quietly assume local figures for the rest.
    const grouped = groupByTarget([
      { question: byKey('spending:rent'), value: '500.00' },
      { question: byKey('spending:groceries'), value: '380.00' },
      { question: byKey('monthly-income'), value: '2000.00' },
      { question: byKey('balance'), value: '20000.00' },
    ]);

    expect(grouped.get('/api/v1/spending')).toEqual({ rent: '500.00', groceries: '380.00' });
    // And both halves of the money route travel together: it refuses a body carrying only one.
    expect(grouped.get('/api/v1/money')).toEqual({ monthlyIncome: '2000.00', balance: '20000.00' });
  });

  it('routes a profile answer to the profile, not to a field of its own', () => {
    const grouped = groupByTarget([{ question: byKey('lifestyle'), value: 'regular' }]);
    expect(grouped.get('/api/v1/profile')).toEqual({ lifestyle: 'regular' });
  });

  it('knows which questions are picked from a list and which are typed', () => {
    expect(isChoice(byKey('lifestyle'))).toBe(true);
    expect(isChoice(byKey('monthly-income'))).toBe(false);
    expect(isChoice(byKey('spending:rent'))).toBe(false);
  });
});
