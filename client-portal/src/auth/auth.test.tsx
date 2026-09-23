import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { FakeAuth, FakeServer, renderApp } from '../test/fakes';

const EMAIL = 'maya@example.com';
const PASSWORD = 'correct horse battery';

async function signIn(email: string, password: string) {
  const user = userEvent.setup();
  await user.type(await screen.findByLabelText('Email'), email);
  await user.type(screen.getByLabelText('Password'), password);
  await user.click(screen.getByRole('button', { name: 'Sign in' }));
}

describe('signing in', () => {
  it('sends a signed-out visitor to sign-in, then on to where they were headed', async () => {
    renderApp('/setup/location', new FakeAuth().withAccount(EMAIL, PASSWORD));

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument();
    await signIn(EMAIL, PASSWORD);

    expect(await screen.findByRole('heading', { name: 'Where do you live?' })).toBeInTheDocument();
    expect(screen.getByTestId('path')).toHaveTextContent('/setup/location');
  });

  it('explains a wrong password in words and keeps the visitor on the form', async () => {
    renderApp('/signin', new FakeAuth().withAccount(EMAIL, PASSWORD));

    await signIn(EMAIL, 'not it');

    expect(await screen.findByRole('alert')).toHaveTextContent('That email and password do not match an account.');
    expect(screen.getByTestId('path')).toHaveTextContent('/signin');
  });
});

describe('signing up', () => {
  it('signs the new user straight in and starts setup', async () => {
    const user = userEvent.setup();
    renderApp('/signup');

    await user.type(await screen.findByLabelText('Email'), EMAIL);
    await user.type(screen.getByLabelText(/^Password/), PASSWORD);
    await user.click(screen.getByRole('button', { name: 'Create account' }));

    expect(await screen.findByRole('heading', { name: 'Where do you live?' })).toBeInTheDocument();
  });

  it('says to check the inbox when the address must be confirmed first, rather than going blank', async () => {
    const user = userEvent.setup();
    const auth = new FakeAuth();
    auth.requireConfirmation = true;
    renderApp('/signup', auth);

    await user.type(await screen.findByLabelText('Email'), EMAIL);
    await user.type(screen.getByLabelText(/^Password/), PASSWORD);
    await user.click(screen.getByRole('button', { name: 'Create account' }));

    expect(await screen.findByRole('heading', { name: 'Check your email' })).toBeInTheDocument();
    expect(screen.getByText(EMAIL)).toBeInTheDocument();
  });

  it('refuses a short password before asking the server, and says what is needed', async () => {
    const user = userEvent.setup();
    const auth = new FakeAuth();
    renderApp('/signup', auth);

    await user.type(await screen.findByLabelText('Email'), EMAIL);
    await user.type(screen.getByLabelText(/^Password/), 'short');
    await user.click(screen.getByRole('button', { name: 'Create account' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Use at least 8 characters');
    expect(auth.tokensIssued).toBe(0);
  });
});

describe('the session', () => {
  it("sends the signed-in user's access token with every request", async () => {
    const { server } = renderApp('/setup/location', new FakeAuth().signedInAs(EMAIL));

    await screen.findByLabelText(/^Country/);

    expect(server.requests.length).toBeGreaterThan(0);
    expect(server.requests.every((r) => r.authorization === 'Bearer token-1')).toBe(true);
  });

  it('returns the user to sign-in, saying why, when the server refuses their token', async () => {
    // The alternative - rendering the page with nothing in it - looks exactly like a data bug, and
    // nobody would think to sign in again.
    const server = new FakeServer().on('/api/catalogue/countries', { status: 401 });
    renderApp('/setup/location', new FakeAuth().signedInAs(EMAIL), server);

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('Your session ended');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('returns the user to sign-in when the session ends in the background', async () => {
    const auth = new FakeAuth().signedInAs(EMAIL);
    renderApp('/setup/location', auth);
    await screen.findByLabelText(/^Country/);

    auth.expire();

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument();
  });

  it('forgets setup answers on sign-out, so the next person at this browser does not inherit them', async () => {
    const user = userEvent.setup();
    renderApp('/setup/location', new FakeAuth().signedInAs(EMAIL));
    await user.selectOptions(await screen.findByLabelText(/^Country/), 'LB');
    await waitFor(() => expect(sessionStorage.length).toBeGreaterThan(0));

    await user.click(screen.getByRole('button', { name: 'Sign out' }));

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument();
    expect(sessionStorage.length).toBe(0);
  });
});
