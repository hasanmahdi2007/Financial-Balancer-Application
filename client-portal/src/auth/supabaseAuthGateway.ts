import { createClient, type AuthError, type Session } from '@supabase/supabase-js';
import type { ClientConfig } from '../config';
import { AuthFailure, type AuthGateway, type AuthSession } from './AuthGateway';

/**
 * Supabase's error codes, in words a person can act on.
 *
 * Lookup data rather than a chain of ifs: a code Supabase adds later is one row here, and anything
 * unlisted falls back to Supabase's own English message, which is readable if not always kind.
 */
const FAILURE_WORDING: Record<string, string> = {
  invalid_credentials: 'That email and password do not match an account. Check both and try again.',
  email_not_confirmed:
    'Your email address is not confirmed yet. Open the link we sent you, then sign in.',
  user_already_exists: 'An account with that email already exists. Try signing in instead.',
  email_exists: 'An account with that email already exists. Try signing in instead.',
  weak_password: 'That password is too easy to guess. Use at least 8 characters, mixing letters and numbers.',
  over_request_rate_limit: 'Too many attempts in a short time. Wait a minute, then try again.',
  over_email_send_rate_limit:
    'We have sent too many emails to that address recently. Wait a few minutes, then try again.',
  signup_disabled: 'New accounts cannot be created right now.',
  validation_failed: 'Check that the email address is complete, like name@example.com.',
};

function toFailure(error: AuthError): AuthFailure {
  const wording = error.code ? FAILURE_WORDING[error.code] : undefined;
  return new AuthFailure(wording ?? error.message);
}

function toSession(session: Session | null): AuthSession | null {
  return session ? { accessToken: session.access_token, email: session.user.email ?? null } : null;
}

export function createSupabaseAuthGateway(config: ClientConfig): AuthGateway {
  const supabase = createClient(config.supabaseUrl, config.supabaseAnonKey, {
    auth: {
      // Keeps the user signed in across reloads, and refreshes the access token shortly before it
      // expires rather than after a request has already been refused with it.
      persistSession: true,
      autoRefreshToken: true,
      // Picks up the session from the link in the confirmation email.
      detectSessionInUrl: true,
    },
  });

  return {
    async currentSession() {
      // getSession refreshes an expired token before returning it, so a tab left open overnight
      // sends a live token rather than one the gateway will refuse.
      const { data, error } = await supabase.auth.getSession();
      if (error) return null;
      return toSession(data.session);
    },

    async signIn(email, password) {
      const { error } = await supabase.auth.signInWithPassword({ email, password });
      if (error) throw toFailure(error);
    },

    async signUp(email, password) {
      const { data, error } = await supabase.auth.signUp({
        email,
        password,
        options: { emailRedirectTo: window.location.origin },
      });
      if (error) throw toFailure(error);
      return data.session ? { kind: 'signed-in' } : { kind: 'confirm-email', email };
    },

    async signOut() {
      // 'local' ends this browser's session even when the network is down, which is when a
      // rejected token most often needs clearing.
      await supabase.auth.signOut({ scope: 'local' });
    },

    onSessionChange(listener) {
      const { data } = supabase.auth.onAuthStateChange((_event, session) => {
        listener(toSession(session));
      });
      return () => data.subscription.unsubscribe();
    },
  };
}
