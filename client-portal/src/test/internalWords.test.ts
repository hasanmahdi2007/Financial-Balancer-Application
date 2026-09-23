import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { INTERNAL_WORDS, internalWordsIn, withoutComments } from './internalWords';

const srcRoot = resolve(process.cwd(), 'src');

function sourceFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) return name === 'test' ? [] : sourceFiles(path);
    return /\.tsx?$/.test(name) && !/\.test\.tsx?$/.test(name) ? [path] : [];
  });
}

describe('server wording', () => {
  it('reads the constant names from the server source rather than from a list kept here', () => {
    // A sanity check on the reader itself: if it silently found nothing, the test below would pass
    // against every file forever.
    expect(INTERNAL_WORDS).toEqual(expect.arrayContaining(['CROWDSOURCED', 'DINING_OUT', 'LOCKED', 'STALE']));
  });

  it('is never re-mapped in the client, so it cannot drift from the server', () => {
    // A label map keyed by constant names would be a second copy of the policy on `Confidence` and
    // `SpendCategory`, and would go stale the first time a tier or category is added. The client
    // renders the words the API sends; no constant name has any business in its code.
    const offenders = sourceFiles(srcRoot)
      .map((file) => ({ file: relative(srcRoot, file), words: internalWordsIn(withoutComments(readFileSync(file, 'utf8'))) }))
      .filter(({ words }) => words.length > 0);
    expect(offenders).toEqual([]);
  });
});
