import { render } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router';
import { App } from '../App';
import { AuthFailure, type AuthGateway, type AuthSession, type SignUpOutcome } from '../auth/AuthGateway';
import cities_LB from './fixtures/cities-LB.json';
import cities_US from './fixtures/cities-US.json';
import countries from './fixtures/countries.json';
import manualForm_LB from './fixtures/manual-form-LB.json';

/**
 * An identity provider held in memory: the second implementation of `AuthGateway`, standing in for
 * Supabase so no test needs the network or a real account.
 */
export class FakeAuth implements AuthGateway {
  private session: AuthSession | null = null;
  private readonly accounts = new Map<string, string>();
  private readonly listeners = new Set<(session: AuthSession | null) => void>();
  /** When true, sign-up behaves like a project that requires a confirmed email address. */
  requireConfirmation = false;
  tokensIssued = 0;

  withAccount(email: string, password: string): this {
    this.accounts.set(email, password);
    return this;
  }

  signedInAs(email: string): this {
    this.session = this.issue(email);
    return this;
  }

  /** What Supabase does when a refresh token is refused: the session simply ends. */
  expire(): void {
    this.session = null;
    this.emit();
  }

  async currentSession() {
    return this.session;
  }

  async signIn(email: string, password: string) {
    if (this.accounts.get(email) !== password) {
      throw new AuthFailure('That email and password do not match an account. Check both and try again.');
    }
    this.session = this.issue(email);
    this.emit();
  }

  async signUp(email: string, password: string): Promise<SignUpOutcome> {
    if (this.accounts.has(email)) {
      throw new AuthFailure('An account with that email already exists. Try signing in instead.');
    }
    this.accounts.set(email, password);
    if (this.requireConfirmation) return { kind: 'confirm-email', email };
    this.session = this.issue(email);
    this.emit();
    return { kind: 'signed-in' };
  }

  async signOut() {
    this.session = null;
    this.emit();
  }

  onSessionChange(listener: (session: AuthSession | null) => void) {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  private issue(email: string): AuthSession {
    this.tokensIssued += 1;
    return { accessToken: `token-${this.tokensIssued}`, email };
  }

  private emit() {
    for (const listener of this.listeners) listener(this.session);
  }
}

export interface Recorded {
  path: string;
  authorization: string | null;
}

type Reply = { status: number; body?: unknown } | (() => Promise<Response>);

/**
 * Answers the catalogue routes from fixtures recorded against a real budget-core, and records every
 * request so a test can check what was sent.
 */
export class FakeServer {
  readonly requests: Recorded[] = [];
  private readonly routes = new Map<string, Reply>([
    ['/api/catalogue/countries', { status: 200, body: countries }],
    ['/api/catalogue/countries/LB/cities', { status: 200, body: cities_LB }],
    ['/api/catalogue/countries/US/cities', { status: 200, body: cities_US }],
    ['/api/catalogue/countries/LB/manual-form', { status: 200, body: manualForm_LB }],
  ]);

  on(path: string, reply: Reply): this {
    this.routes.set(path, reply);
    return this;
  }

  readonly fetch: typeof fetch = async (input, init) => {
    const path = String(input);
    const headers = new Headers(init?.headers);
    this.requests.push({ path, authorization: headers.get('Authorization') });
    const reply = this.routes.get(path);
    if (!reply) return new Response(null, { status: 404 });
    if (typeof reply === 'function') return reply();
    return new Response(reply.body === undefined ? null : JSON.stringify(reply.body), {
      status: reply.status,
      headers: { 'Content-Type': 'application/json' },
    });
  };
}

/** Shows the current path, so a test can assert where the app sent the user. */
function WhereAmI() {
  const location = useLocation();
  // A span, not an <output>: that element carries the "status" role, and this helper must not turn
  // up in a test asking what the app told the user.
  return <span data-testid="path">{location.pathname}</span>;
}

export function renderApp(path: string, auth = new FakeAuth(), server = new FakeServer()) {
  const view = render(
    <MemoryRouter initialEntries={[path]}>
      <App auth={auth} fetchImpl={server.fetch} />
      <WhereAmI />
    </MemoryRouter>,
  );
  return { ...view, auth, server };
}
