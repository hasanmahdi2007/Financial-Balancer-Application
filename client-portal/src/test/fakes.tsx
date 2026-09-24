import { render } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router';
import { App } from '../App';
import { AuthFailure, type AuthGateway, type AuthSession, type SignUpOutcome } from '../auth/AuthGateway';
import type { BankWindow } from '../bank/BankWindow';
import affordBand from './fixtures/afford-band.json';
import choices from './fixtures/choices.json';
import cities_LB from './fixtures/cities-LB.json';
import cities_US from './fixtures/cities-US.json';
import countries from './fixtures/countries.json';
import goalCar from './fixtures/goal-car.json';
import goalsFixture from './fixtures/goals.json';
import manualForm_LB from './fixtures/manual-form-LB.json';
import money from './fixtures/money.json';
import planFixture from './fixtures/plan.json';
import planHistory from './fixtures/plan-history.json';
import profile from './fixtures/profile.json';
import questionsFixture from './fixtures/questions-new.json';
import rebalance from './fixtures/rebalance.json';
import spending from './fixtures/spending.json';

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
  method: string;
  path: string;
  authorization: string | null;
  /** What was sent, already parsed, or null for a request with no body. */
  body: unknown;
}

type Reply = { status: number; body?: unknown } | (() => Promise<Response>);

/**
 * Answers the routes from fixtures recorded against a real budget-core, and records every request so
 * a test can check what was sent.
 *
 * Routes are keyed by method and path together, because the second half of this client writes as
 * well as reads and `PUT /api/v1/profile` is not the same route as `GET /api/v1/profile`. A bare
 * path still registers a GET, which is what every catalogue route is.
 */
export class FakeServer {
  readonly requests: Recorded[] = [];
  private readonly routes = new Map<string, Reply>([
    ['GET /api/catalogue/countries', { status: 200, body: countries }],
    ['GET /api/catalogue/countries/LB/cities', { status: 200, body: cities_LB }],
    ['GET /api/catalogue/countries/US/cities', { status: 200, body: cities_US }],
    ['GET /api/catalogue/countries/LB/manual-form', { status: 200, body: manualForm_LB }],
    ['GET /api/v1/choices', { status: 200, body: choices }],
    ['GET /api/v1/questions', { status: 200, body: questionsFixture }],
    ['GET /api/v1/profile', { status: 200, body: profile }],
    ['PUT /api/v1/profile', { status: 200, body: profile }],
    ['PUT /api/v1/money', { status: 200, body: money }],
    ['PUT /api/v1/spending', { status: 200, body: spending }],
    ['GET /api/v1/plan', { status: 200, body: planFixture }],
    ['POST /api/v1/plan', { status: 200, body: planFixture }],
    ['GET /api/v1/plan/history', { status: 200, body: planHistory }],
    ['GET /api/v1/goals', { status: 200, body: goalsFixture }],
    ['POST /api/v1/goals', { status: 201, body: goalCar }],
    ['POST /api/v1/decisions/afford', { status: 200, body: affordBand }],
    ['POST /api/v1/decisions/rebalance', { status: 200, body: rebalance }],
  ]);

  /** `on('/api/x', …)` registers a GET; `on('PUT /api/x', …)` registers that method. */
  on(route: string, reply: Reply): this {
    this.routes.set(route.includes(' ') ? route : `GET ${route}`, reply);
    return this;
  }

  /** Every request made to one route, in order, so a test can assert what was actually sent. */
  sentTo(method: string, path: string): unknown[] {
    return this.requests.filter((r) => r.method === method && r.path === path).map((r) => r.body);
  }

  readonly fetch: typeof fetch = async (input, init) => {
    const path = String(input);
    const method = (init?.method ?? 'GET').toUpperCase();
    const headers = new Headers(init?.headers);
    const raw = typeof init?.body === 'string' ? init.body : null;
    this.requests.push({
      method,
      path,
      authorization: headers.get('Authorization'),
      body: raw === null ? null : JSON.parse(raw),
    });
    const reply = this.routes.get(`${method} ${path}`);
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

export function renderApp(path: string, auth = new FakeAuth(), server = new FakeServer(), bankWindow?: BankWindow) {
  const view = render(
    <MemoryRouter initialEntries={[path]}>
      <App auth={auth} fetchImpl={server.fetch} bankWindow={bankWindow} />
      <WhereAmI />
    </MemoryRouter>,
  );
  return { ...view, auth, server };
}
