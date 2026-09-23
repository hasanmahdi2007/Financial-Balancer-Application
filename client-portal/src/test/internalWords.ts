import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, resolve } from 'node:path';

/**
 * Every enum constant name in budget-core, read from the Java source on each run.
 *
 * Read rather than listed so the check cannot fall behind: a verdict, band or tier added on the
 * server is covered here the moment it is written, without anyone remembering this file exists.
 */
// Relative to the working directory, which Vitest sets to client-portal. import.meta.url is not a
// file URL under jsdom.
const javaRoot = resolve(process.cwd(), '../budget-core/src/main/java');

function javaFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    return statSync(path).isDirectory() ? javaFiles(path) : name.endsWith('.java') ? [path] : [];
  });
}

/** Strips comments, so javadoc prose never registers as a constant (or hides one). */
export function withoutComments(source: string): string {
  return source.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/(^|[^:'"])\/\/.*$/gm, '$1');
}

function enumConstants(source: string): string[] {
  const code = withoutComments(source);
  const match = /\benum\s+\w+[^{]*\{([\s\S]*?)(;|\}\s*$)/.exec(code);
  if (!match?.[1]) return [];
  // Constants are the top-level comma-separated entries before the first semicolon; each may carry
  // constructor arguments in parentheses, which are dropped before splitting.
  let depth = 0;
  let flat = '';
  for (const ch of match[1]) {
    if (ch === '(') depth++;
    if (depth === 0) flat += ch;
    if (ch === ')') depth--;
  }
  return flat
    .split(',')
    .map((part) => part.trim())
    .filter((name) => /^[A-Z][A-Z0-9_]+$/.test(name));
}

export const INTERNAL_WORDS: readonly string[] = [
  ...new Set(javaFiles(javaRoot).flatMap((file) => enumConstants(readFileSync(file, 'utf8')))),
].sort();

/** The constant names that appear, as whole words, in some text. */
export function internalWordsIn(text: string): string[] {
  return INTERNAL_WORDS.filter((word) => new RegExp(`\\b${word}\\b`).test(text));
}
