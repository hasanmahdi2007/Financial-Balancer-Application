/**
 * Pulls out the wording every question repeats, so a caveat is read once rather than ten times.
 *
 * The server assembles each question's `why` from the same parts - why we are asking, plus the
 * country's caveat - so on a ten-question form the same three lines arrive ten times. On screen that
 * buries the one sentence per box that actually differs, and a form nobody reads is a form people
 * abandon.
 *
 * Derived from the answers themselves rather than from a copy of the caveat kept here: if the server
 * stops repeating itself, the shared part is empty and every question simply keeps its own words.
 */
function sentences(text: string): string[] {
  return text.match(/[^.!?]+[.!?]*\s*/g) ?? [text];
}

export interface SharedWording {
  /** The sentences every question ended with, or null when they share no ending. */
  shared: string | null;
  /** Each question's own wording, in the order given, with the shared ending removed. */
  particular: string[];
}

export function liftSharedEnding(texts: string[]): SharedWording {
  if (texts.length < 2) return { shared: null, particular: texts };

  const split = texts.map(sentences);
  const shortest = Math.min(...split.map((parts) => parts.length));
  let common = 0;
  while (common < shortest) {
    const candidate = split[0]![split[0]!.length - 1 - common]!.trim();
    if (!split.every((parts) => parts[parts.length - 1 - common]!.trim() === candidate)) break;
    common += 1;
  }
  if (common === 0) return { shared: null, particular: texts };

  return {
    shared: split[0]!
      .slice(split[0]!.length - common)
      .join('')
      .trim(),
    particular: split.map((parts) => parts.slice(0, parts.length - common).join('').trim()),
  };
}
