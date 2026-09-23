import { useState, type FormEvent } from 'react';
import { Link, Navigate, useLocation } from 'react-router';
import { AuthFailure } from './AuthGateway';
import { useAuth } from './AuthProvider';

const FALLBACK = "We couldn't sign you in just now. Check your connection, then try again.";

export function destinationAfterSignIn(state: unknown): string {
  const from = (state as { from?: unknown } | null)?.from;
  return typeof from === 'string' && from.startsWith('/') ? from : '/setup/location';
}

export function SignInPage() {
  const { status, gateway, notice } = useAuth();
  const location = useLocation();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  if (status === 'signed-in') return <Navigate to={destinationAfterSignIn(location.state)} replace />;

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await gateway.signIn(email.trim(), password);
      // The session listener flips status to signed-in, which redirects above.
    } catch (failure) {
      setError(failure instanceof AuthFailure ? failure.message : FALLBACK);
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="card card--narrow">
      <h1>Sign in</h1>
      {notice ? (
        <p className="banner banner--info" role="status">
          {notice}
        </p>
      ) : null}
      <form onSubmit={submit} className="form">
        <label className="field">
          <span className="field__label">Email</span>
          <input type="email" autoComplete="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
        </label>
        <label className="field">
          <span className="field__label">Password</span>
          <input
            type="password"
            autoComplete="current-password"
            required
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
        </label>
        {error ? (
          <p className="field__error" role="alert">
            {error}
          </p>
        ) : null}
        <button type="submit" className="button" disabled={busy}>
          {busy ? 'Signing in…' : 'Sign in'}
        </button>
      </form>
      <p className="aside">
        New here? <Link to="/signup">Create an account</Link>
      </p>
    </section>
  );
}
