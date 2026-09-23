import { useState, type FormEvent } from 'react';
import { Link, Navigate } from 'react-router';
import { AuthFailure } from './AuthGateway';
import { useAuth } from './AuthProvider';

const FALLBACK = "We couldn't create your account just now. Check your connection, then try again.";
const MIN_PASSWORD = 8;

export function SignUpPage() {
  const { status, gateway } = useAuth();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [awaitingConfirmation, setAwaitingConfirmation] = useState<string | null>(null);

  if (status === 'signed-in') return <Navigate to="/setup/location" replace />;

  if (awaitingConfirmation) {
    return (
      <section className="card card--narrow">
        <h1>Check your email</h1>
        <p>
          We sent a link to <strong>{awaitingConfirmation}</strong>. Open it to confirm the address, and you will be
          brought back here signed in.
        </p>
        <p className="aside">
          Already confirmed? <Link to="/signin">Sign in</Link>
        </p>
      </section>
    );
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (password.length < MIN_PASSWORD) {
      setError(`Use at least ${MIN_PASSWORD} characters for your password.`);
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const outcome = await gateway.signUp(email.trim(), password);
      if (outcome.kind === 'confirm-email') setAwaitingConfirmation(outcome.email);
    } catch (failure) {
      setError(failure instanceof AuthFailure ? failure.message : FALLBACK);
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="card card--narrow">
      <h1>Create your account</h1>
      <p className="lede">
        We will ask where you live and what you earn, then show you what your goals really cost in your city.
      </p>
      <form onSubmit={submit} className="form">
        <label className="field">
          <span className="field__label">Email</span>
          <input type="email" autoComplete="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
        </label>
        <label className="field">
          <span className="field__label">Password</span>
          <span className="field__help">At least {MIN_PASSWORD} characters.</span>
          <input
            type="password"
            autoComplete="new-password"
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
          {busy ? 'Creating your account…' : 'Create account'}
        </button>
      </form>
      <p className="aside">
        Already have an account? <Link to="/signin">Sign in</Link>
      </p>
    </section>
  );
}
