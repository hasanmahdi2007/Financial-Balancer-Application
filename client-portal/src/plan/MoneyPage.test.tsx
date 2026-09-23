import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { FakeAuth, FakeServer, renderApp } from '../test/fakes';
import money from '../test/fixtures/money.json';

const signedIn = () => new FakeAuth().signedInAs('maya@example.com');
const SAVING = 'How much do you already move into savings each month?';

describe('changing your money', () => {
  it('keeps what the user already saves when they change something else', async () => {
    // Left out of PUT /api/v1/money, alreadySaving is cleared - checked against the live server. So
    // editing the income must send the savings figure back, or the user silently loses it.
    const server = new FakeServer().on('/api/v1/money', { status: 200, body: { ...money, alreadySaving: '150.00' } });
    renderApp('/money', signedIn(), server);
    const user = userEvent.setup();
    const income = await screen.findByLabelText('How much arrives in your account each month?');
    await user.clear(income);
    await user.type(income, '2100');
    await user.click(screen.getByRole('button', { name: 'Save and replan' }));
    await screen.findByRole('heading', { name: 'Your plan' });

    expect(server.sentTo('PUT', '/api/v1/money')).toEqual([
      { monthlyIncome: '2100', balance: money.balance, alreadySaving: '150.00' },
    ]);
    expect(server.sentTo('POST', '/api/v1/plan')).toHaveLength(1);
  });

  it('leaves it out when the box is emptied, so a connected bank can answer it', async () => {
    const server = new FakeServer().on('/api/v1/money', { status: 200, body: { ...money, alreadySaving: '150.00' } });
    renderApp('/money', signedIn(), server);
    const user = userEvent.setup();
    await user.clear(await screen.findByLabelText(SAVING));
    await user.click(screen.getByRole('button', { name: 'Save and replan' }));
    await screen.findByRole('heading', { name: 'Your plan' });

    expect(server.sentTo('PUT', '/api/v1/money')[0]).not.toHaveProperty('alreadySaving');
  });

  it("explains the figure in the server's words", async () => {
    const server = new FakeServer().on('/api/v1/money', { status: 200, body: money });
    renderApp('/money', signedIn(), server);

    expect(await screen.findByText(money.alreadySavingExplanation)).toBeInTheDocument();
    expect(screen.getByLabelText(SAVING)).toHaveValue('');
  });
});
