import { useCallback, useEffect, useState } from 'react';
import { ApiError, SessionEndedError } from './http';

export type Remote<T> =
  | { state: 'loading' }
  | { state: 'failed'; message: string; retry(): void }
  | { state: 'ready'; data: T };

const FALLBACK = 'Something went wrong. Try again in a moment.';

/**
 * Loads one thing and tracks whether it has arrived. The loader re-runs whenever `key` changes,
 * and a response for a previous key is dropped rather than rendered - picking Lebanon and then the
 * United States quickly must never show Lebanon's cities under the United States.
 */
export function useRemote<T>(key: string | null, load: (signal: AbortSignal) => Promise<T>): Remote<T> {
  const [result, setResult] = useState<Remote<T>>({ state: 'loading' });
  const [attempt, setAttempt] = useState(0);
  const retry = useCallback(() => setAttempt((n) => n + 1), []);

  useEffect(() => {
    if (key === null) return;
    const controller = new AbortController();
    setResult({ state: 'loading' });
    load(controller.signal).then(
      (data) => {
        if (!controller.signal.aborted) setResult({ state: 'ready', data });
      },
      (error: unknown) => {
        if (controller.signal.aborted) return;
        // The user is already on their way to sign-in; an error panel would flash on the way out.
        if (error instanceof SessionEndedError) return;
        const message = error instanceof ApiError ? error.message : FALLBACK;
        setResult({ state: 'failed', message, retry });
      },
    );
    return () => controller.abort();
    // `load` is deliberately not a dependency: callers pass an inline closure, and the key is what
    // says the request has changed.
  }, [key, attempt, retry]);

  return result;
}
