import type { OpenQuestion } from '../api/plan';

/**
 * Where each answer goes, worked out from the server's own instruction rather than from its key.
 *
 * Every open question carries `answerWith`, a string like `PUT /api/v1/spending rent`. Reading that
 * is what keeps the order and the content of setup on the server: a new question can be added there
 * and answered here without this client learning its name. The alternative - a switch on `key` -
 * would mean no question could be added without a matching branch shipped in the browser.
 */
export interface ParsedTarget {
  method: string;
  path: string;
  /** The field or category key the answer is sent under, absent for a whole-body route. */
  field: string | null;
}

const TARGET = /^(GET|PUT|POST|DELETE)\s+(\/\S*)(?:\s+(\S+))?$/;

export function parseTarget(answerWith: string): ParsedTarget | null {
  const match = TARGET.exec(answerWith.trim());
  if (!match) return null;
  return { method: match[1]!, path: match[2]!, field: match[3] ?? null };
}

export interface Answer {
  question: OpenQuestion;
  /** Exactly what the user typed or picked - an amount stays the string it was entered as. */
  value: string;
}

/**
 * The answers grouped by the request that carries them, as `{path: {field: value}}`.
 *
 * Grouping matters because two of these routes are not per-field. `PUT /api/v1/money` refuses a body
 * with only one of its two figures, and `PUT /api/v1/spending` *replaces* the whole map rather than
 * merging into it - sending one category at a time would silently drop every category answered
 * before it. Both are the documented behaviour, and both are only safe if the client sends each
 * route's answers together, which is what this returns.
 */
export function groupByTarget(answers: Answer[]): Map<string, Record<string, string>> {
  const grouped = new Map<string, Record<string, string>>();
  for (const answer of answers) {
    const target = parseTarget(answer.question.answerWith);
    // A question whose instruction we cannot read is skipped rather than guessed at: sending an
    // answer to the wrong field would be worse than not sending it, and the question stays open, so
    // the server asks again rather than the answer vanishing silently.
    if (!target || !target.field) continue;
    const body = grouped.get(target.path) ?? {};
    body[target.field] = answer.value;
    grouped.set(target.path, body);
  }
  return grouped;
}

/** True when the question expects one of a fixed set rather than an amount. */
export function isChoice(question: OpenQuestion): boolean {
  return question.choices.length > 0;
}
