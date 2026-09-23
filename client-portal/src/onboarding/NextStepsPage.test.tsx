import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { FakeAuth, FakeServer, renderApp } from '../test/fakes';
import questions from '../test/fixtures/manual-form-LB.json';

const signedIn = () => new FakeAuth().signedInAs('maya@example.com');

describe('confirming where the user lives', () => {
  it('saves a listed city by its id, and goes on to the questions', async () => {
    const { server } = renderApp('/setup/location', signedIn());
    const user = userEvent.setup();
    await user.selectOptions(await screen.findByLabelText(/^Country/), 'LB');
    await user.selectOptions(await screen.findByLabelText(/^City/), 'beirut');
    await user.click(await screen.findByRole('button', { name: 'Continue with Beirut' }));
    await screen.findByRole('heading', { name: 'Beirut it is' });

    // Nothing is saved just by arriving: this page is also where Back lands.
    expect(server.sentTo('PUT', '/api/v1/profile')).toEqual([]);
    await user.click(screen.getByRole('button', { name: 'Continue' }));

    await screen.findByRole('heading', { name: 'A few questions about how you live' });
    expect(server.sentTo('PUT', '/api/v1/profile')).toEqual([
      expect.objectContaining({ country: 'LB', city: 'beirut', cityNotListed: null }),
    ]);
  });

  it('keeps how the user lives when they change where they live', async () => {
    // PUT /api/v1/profile replaces the whole profile. The saved one says "Out regularly"; moving city
    // must not quietly reset it.
    const { server } = renderApp('/setup/location', signedIn());
    const user = userEvent.setup();
    await user.selectOptions(await screen.findByLabelText(/^Country/), 'LB');
    await user.selectOptions(await screen.findByLabelText(/^City/), 'beirut');
    await user.click(await screen.findByRole('button', { name: 'Continue with Beirut' }));
    await user.click(await screen.findByRole('button', { name: 'Continue' }));
    await screen.findByRole('heading', { name: 'A few questions about how you live' });

    expect(server.sentTo('PUT', '/api/v1/profile')[0]).toMatchObject({
      lifestyle: 'regular',
      leastForEnjoyingLife: null,
      incomeArrivesTaxed: true,
    });
  });

  it('sends only the figures the user changed as their own, and leaves ours as ours', async () => {
    // An untouched answer is still our country estimate. Sent as an override, it would be labelled
    // "Your own figure" on every screen after - a claim the user never made.
    const rent = questions[0]!;
    const server = new FakeServer()
      .on('GET /api/v1/profile', { status: 404 })
      .on(`PUT /api/v1/overrides/${rent.category}`, { status: 200, body: {} });
    renderApp('/setup/LB/my-city', signedIn(), server);
    const user = userEvent.setup();
    await user.type(await screen.findByLabelText(/What is your city called/), 'Zahlé');
    const first = screen.getAllByRole('textbox')[1]!;
    await user.clear(first);
    await user.type(first, '410.50');
    await user.click(screen.getByRole('button', { name: 'Save and continue' }));
    expect(await screen.findByText(/the figure you changed for Zahlé/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    await screen.findByRole('heading', { name: 'A few questions about how you live' });

    expect(server.sentTo('PUT', '/api/v1/profile')).toEqual([
      expect.objectContaining({ country: 'LB', city: null, cityNotListed: 'Zahlé' }),
    ]);
    const overrides = server.requests.filter((r) => r.path.startsWith('/api/v1/overrides/'));
    expect(overrides.map((r) => [r.path, r.body])).toEqual([
      [`/api/v1/overrides/${rent.category}`, { amount: '410.50' }],
    ]);
  });

  it('says what went wrong and stays put when the save is refused', async () => {
    const server = new FakeServer().on('PUT /api/v1/profile', {
      status: 400,
      body: { detail: 'We do not hold figures for that city.' },
    });
    renderApp('/setup/location', signedIn(), server);
    const user = userEvent.setup();
    await user.selectOptions(await screen.findByLabelText(/^Country/), 'LB');
    await user.selectOptions(await screen.findByLabelText(/^City/), 'beirut');
    await user.click(await screen.findByRole('button', { name: 'Continue with Beirut' }));
    await user.click(await screen.findByRole('button', { name: 'Continue' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('We do not hold figures for that city.');
    expect(screen.getByTestId('path')).toHaveTextContent('/setup/next');
  });
});
