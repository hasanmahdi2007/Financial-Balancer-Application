import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import type { AuthGateway, AuthSession } from './AuthGateway';

export type AuthStatus = 'checking' | 'signed-in' | 'signed-out';

interface AuthContextValue {
  status: AuthStatus;
  session: AuthSession | null;
  gateway: AuthGateway;
  /**
   * Why the user was last returned to sign-in, if it was not their own choice. Shown on the sign-in
   * page so an expired session reads as an expired session, not as a bug.
   */
  notice: string | null;
  /** Ends the session because the server refused its token. */
  endSession(notice: string): void;
  signOut(): Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ gateway, children }: { gateway: AuthGateway; children: ReactNode }) {
  const [session, setSession] = useState<AuthSession | null>(null);
  const [status, setStatus] = useState<AuthStatus>('checking');
  const [notice, setNotice] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    const apply = (next: AuthSession | null) => {
      if (!active) return;
      setSession(next);
      setStatus(next ? 'signed-in' : 'signed-out');
      if (next) setNotice(null);
    };
    const unsubscribe = gateway.onSessionChange(apply);
    gateway.currentSession().then(apply, () => apply(null));
    return () => {
      active = false;
      unsubscribe();
    };
  }, [gateway]);

  const endSession = useCallback(
    (reason: string) => {
      setNotice(reason);
      setSession(null);
      setStatus('signed-out');
      void gateway.signOut().catch(() => undefined);
    },
    [gateway],
  );

  const signOut = useCallback(async () => {
    setNotice(null);
    await gateway.signOut();
    setSession(null);
    setStatus('signed-out');
  }, [gateway]);

  const value = useMemo(
    () => ({ status, session, gateway, notice, endSession, signOut }),
    [status, session, gateway, notice, endSession, signOut],
  );
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext);
  if (!value) throw new Error('useAuth must be used inside <AuthProvider>.');
  return value;
}
