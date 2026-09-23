import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { useAuth } from '../auth/AuthProvider';

/** One answer from the "my city isn't listed" form, kept as the decimal string the user typed. */
export interface ManualAnswer {
  question: string;
  amount: string;
}

export type ChosenLocation =
  | { kind: 'listed'; countryCode: string; cityId: string; cityName: string }
  | {
      kind: 'unlisted';
      countryCode: string;
      /** Shown back to the user and nothing else - it is never looked up, so it can never mis-match. */
      cityLabel: string;
      answers: ManualAnswer[];
    };

export interface Draft {
  countryCode?: string;
  location?: ChosenLocation;
}

/**
 * What the user has answered so far in setup, kept until it can be saved to their profile.
 *
 * Amounts stay strings end to end. Parsing "350.10" into a JavaScript number would make it a binary
 * float, which is exactly the representation the server refuses to use for money.
 *
 * Held in sessionStorage so a reload mid-setup does not lose the answers, and cleared on sign-out so
 * the next person to use this tab does not inherit them.
 */
const STORAGE_KEY = 'fb.onboarding.draft';

function readStored(): Draft {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY);
    return raw ? (JSON.parse(raw) as Draft) : {};
  } catch {
    return {};
  }
}

function writeStored(draft: Draft): void {
  try {
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(draft));
  } catch {
    // Private windows can refuse storage. The draft still lives in memory for this visit.
  }
}

interface DraftContextValue {
  draft: Draft;
  chooseCountry(countryCode: string): void;
  chooseLocation(location: ChosenLocation): void;
}

const DraftContext = createContext<DraftContextValue | null>(null);

export function OnboardingDraftProvider({ children }: { children: ReactNode }) {
  const { status } = useAuth();
  const [draft, setDraft] = useState<Draft>(readStored);

  useEffect(() => {
    if (status !== 'signed-out') return;
    setDraft({});
    try {
      sessionStorage.removeItem(STORAGE_KEY);
    } catch {
      // Nothing stored, then.
    }
  }, [status]);

  const update = useCallback((next: Draft) => {
    setDraft(next);
    writeStored(next);
  }, []);

  const chooseCountry = useCallback(
    (countryCode: string) =>
      update(
        // A location chosen under another country no longer applies.
        draft.countryCode === countryCode ? draft : { countryCode },
      ),
    [draft, update],
  );

  const chooseLocation = useCallback(
    (location: ChosenLocation) => update({ countryCode: location.countryCode, location }),
    [update],
  );

  const value = useMemo(() => ({ draft, chooseCountry, chooseLocation }), [draft, chooseCountry, chooseLocation]);
  return <DraftContext.Provider value={value}>{children}</DraftContext.Provider>;
}

export function useOnboardingDraft(): DraftContextValue {
  const value = useContext(DraftContext);
  if (!value) throw new Error('useOnboardingDraft must be used inside <OnboardingDraftProvider>.');
  return value;
}
