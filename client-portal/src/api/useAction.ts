import { useCallback, useRef, useState } from 'react';
import { ApiError, SessionEndedError } from './http';

const FALLBACK = 'Something went wrong. Try again in a moment.';

export interface Action<A extends unknown[]> {
  /** True while the request is in flight, so the button that started it can refuse a second press. */
  busy: boolean;
  /** The sentence to show when it failed, already written for a person; null otherwise. */
  error: string | null;
  run(...args: A): Promise<void>;
}

/**
 * The write-side twin of `useRemote`: runs one request on demand and tracks whether it failed.
 *
 * A second press while the first is still running is ignored rather than queued. Saving a goal twice
 * because someone double-clicked would add it twice, and the plan would recompute around a goal they
 * never meant to have.
 */
export function useAction<A extends unknown[]>(perform: (...args: A) => Promise<void>): Action<A> {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const inFlight = useRef(false);
  const latest = useRef(perform);
  latest.current = perform;

  const run = useCallback(async (...args: A) => {
    if (inFlight.current) return;
    inFlight.current = true;
    setBusy(true);
    setError(null);
    try {
      await latest.current(...args);
    } catch (failure) {
      // The user is already on their way to sign-in, which says what happened better than we can.
      if (!(failure instanceof SessionEndedError)) {
        setError(failure instanceof ApiError ? failure.message : FALLBACK);
      }
    } finally {
      inFlight.current = false;
      setBusy(false);
    }
  }, []);

  return { busy, error, run };
}
