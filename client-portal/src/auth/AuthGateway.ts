/**
 * Everything the client needs from an identity provider, and nothing more.
 *
 * Supabase sits behind this rather than being imported by screens, so that exactly one file knows
 * its API. The screens and their tests talk to this interface; the tests pass an in-memory
 * implementation, which is the second implementation that justifies the seam.
 */
export interface AuthSession {
  accessToken: string;
  email: string | null;
}

export type SignUpOutcome =
  /** The account exists and the user is already signed in. */
  | { kind: 'signed-in' }
  /** The project requires a confirmed address before the first sign-in. */
  | { kind: 'confirm-email'; email: string };

export interface AuthGateway {
  /**
   * The current session, refreshed first if its access token has expired. Null when nobody is
   * signed in or the refresh itself was refused.
   */
  currentSession(): Promise<AuthSession | null>;
  signIn(email: string, password: string): Promise<void>;
  signUp(email: string, password: string): Promise<SignUpOutcome>;
  signOut(): Promise<void>;
  /** Called on sign-in, sign-out and every token refresh. Returns an unsubscribe function. */
  onSessionChange(listener: (session: AuthSession | null) => void): () => void;
}

/**
 * A failure the person at the keyboard can act on. `message` is written for them, never a code.
 */
export class AuthFailure extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'AuthFailure';
  }
}
