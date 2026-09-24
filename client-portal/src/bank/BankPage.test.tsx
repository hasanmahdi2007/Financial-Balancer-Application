import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import type { Bank } from '../api/bank';
import { FakeAuth, FakeServer, renderApp } from '../test/fakes';
import type { BankWindow } from './BankWindow';

const signedIn = () => new FakeAuth().signedInAs('maya@example.com');

const EXPLANATION =
  'We can only read your transactions and balances. Nothing here can move money, and your bank login is typed into your bank\'s own window, never into ours.';

const nothingConnected: Bank = { connected: false, connections: [], accounts: [], explanation: EXPLANATION };

const importing: Bank = {
  connected: true,
  connections: [
    {
      id: 7,
      connectedAt: '2026-09-24T10:00:00Z',
      lastUpdated: null,
      status: 'Importing your transactions. This takes a minute or two.',
    },
  ],
  accounts: [],
  explanation: EXPLANATION,
};

const connected: Bank = {
  connected: true,
  connections: [{ id: 7, connectedAt: '2026-09-24T10:00:00Z', lastUpdated: '2026-09-24T10:01:12Z', status: 'Up to date' }],
  accounts: [
    { name: 'Plaid Checking ••0000', kind: 'Bank account', balance: '110.00', meaning: 'In the account', available: '100.00' },
    { name: 'Plaid Credit Card ••3333', kind: 'Credit card', balance: '410.00', meaning: 'Owed on the card', available: null },
  ],
  explanation: EXPLANATION,
};

/** A bank window that answers as a user would: finishing with a token, or closing it. */
class FakeBankWindow implements BankWindow {
  opened: string[] = [];
  constructor(private readonly answer: string | null) {}
  async open(linkToken: string) {
    this.opened.push(linkToken);
    return this.answer;
  }
}

/** GET /api/v1/bank answers each of `states` in turn, then keeps answering the last. */
function bankAnswering(server: FakeServer, ...states: Bank[]) {
  let call = 0;
  return server.on('/api/v1/bank', async () => {
    const body = states[Math.min(call++, states.length - 1)];
    return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
  });
}

describe('connecting a bank', () => {
  it('is offered in the menu of every signed-in page', async () => {
    renderApp('/plan', signedIn());

    const menu = await screen.findByRole('navigation', { name: 'Main' });
    expect(within(menu).getByRole('link', { name: 'Connect your bank' })).toHaveAttribute('href', '/bank');
  });

  it('explains what connecting allows before anyone presses anything', async () => {
    const server = bankAnswering(new FakeServer(), nothingConnected);
    renderApp('/bank', signedIn(), server);

    expect(await screen.findByText(EXPLANATION)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Connect your bank' })).toBeEnabled();
  });

  it("opens the bank's own window and hands the token it returns to the server", async () => {
    const server = bankAnswering(new FakeServer(), nothingConnected, connected).on('POST /api/v1/bank/link-token', {
      status: 200,
      body: { linkToken: 'link-sandbox-123' },
    }).on('POST /api/v1/bank/connections', { status: 202, body: importing });
    const bankWindow = new FakeBankWindow('public-sandbox-456');
    renderApp('/bank', signedIn(), server, bankWindow);
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: 'Connect your bank' }));

    expect(await screen.findByText('Plaid Checking ••0000 · Bank account')).toBeInTheDocument();
    expect(bankWindow.opened).toEqual(['link-sandbox-123']);
    expect(server.sentTo('POST', '/api/v1/bank/connections')).toEqual([{ publicToken: 'public-sandbox-456' }]);
  });

  it('does nothing, and says nothing, when the user closes the window without connecting', async () => {
    const server = bankAnswering(new FakeServer(), nothingConnected).on('POST /api/v1/bank/link-token', {
      status: 200,
      body: { linkToken: 'link-sandbox-123' },
    });
    renderApp('/bank', signedIn(), server, new FakeBankWindow(null));
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: 'Connect your bank' }));

    expect(await screen.findByRole('button', { name: 'Connect your bank' })).toBeEnabled();
    expect(server.sentTo('POST', '/api/v1/bank/connections')).toHaveLength(0);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it("shows the server's sentence when the bank cannot be reached", async () => {
    const detail = 'We could not start connecting to your bank just now. Try again in a few minutes.';
    const server = bankAnswering(new FakeServer(), nothingConnected).on('POST /api/v1/bank/link-token', {
      status: 503,
      body: { title: 'We could not reach your bank', status: 503, detail },
    });
    renderApp('/bank', signedIn(), server, new FakeBankWindow('never-used'));
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: 'Connect your bank' }));

    expect(await screen.findByText(detail)).toBeInTheDocument();
  });
});

describe('a connected bank', () => {
  it('shows every balance with what it means, so money owed is never read as money held', async () => {
    renderApp('/bank', signedIn(), bankAnswering(new FakeServer(), connected));

    expect(await screen.findByText('Plaid Credit Card ••3333 · Credit card')).toBeInTheDocument();
    expect(screen.getByText('Owed on the card')).toBeInTheDocument();
    expect(screen.getByText(/In the account/)).toBeInTheDocument();
    expect(screen.getByText(/Up to date/)).toBeInTheDocument();
  });

  it('will not update the plan until the transactions have arrived', async () => {
    renderApp('/bank', signedIn(), bankAnswering(new FakeServer(), importing));

    expect(await screen.findByText(/Importing your transactions/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Update my plan with this' })).toBeDisabled();
  });

  it('updates the plan from what the bank shows and goes to it', async () => {
    const server = bankAnswering(new FakeServer(), connected);
    renderApp('/bank', signedIn(), server);
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: 'Update my plan with this' }));

    await screen.findByRole('heading', { name: 'Your plan' });
    expect(server.sentTo('POST', '/api/v1/plan')).toHaveLength(1);
    expect(screen.getByTestId('path')).toHaveTextContent('/plan');
  });

  it('asks the bank for anything new without waiting for it', async () => {
    const server = bankAnswering(new FakeServer(), connected).on('POST /api/v1/bank/refresh', {
      status: 202,
      body: { banks: 1 },
    });
    renderApp('/bank', signedIn(), server);
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: 'Check for new transactions' }));

    expect(server.sentTo('POST', '/api/v1/bank/refresh')).toHaveLength(1);
  });

  it('disconnects only after the user confirms, having been told what it deletes', async () => {
    const server = bankAnswering(new FakeServer(), connected, nothingConnected).on('DELETE /api/v1/bank/connections/7', {
      status: 204,
    });
    renderApp('/bank', signedIn(), server);
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: 'Disconnect' }));
    expect(screen.getByText(/deletes everything we imported from this bank/)).toBeInTheDocument();
    expect(server.sentTo('DELETE', '/api/v1/bank/connections/7')).toHaveLength(0);

    await user.click(screen.getByRole('button', { name: 'Yes, disconnect' }));

    expect(await screen.findByRole('button', { name: 'Connect your bank' })).toBeInTheDocument();
    expect(server.sentTo('DELETE', '/api/v1/bank/connections/7')).toHaveLength(1);
  });
});
