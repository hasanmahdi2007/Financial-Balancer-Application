import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { FakeAuth, FakeServer, renderApp } from '../test/fakes';
import { internalWordsIn } from '../test/internalWords';
import questions from '../test/fixtures/questions-new.json';

const signedIn = () => new FakeAuth().signedInAs('maya@example.com');
const question = (key: string) => questions.find((q) => q.key === key)!;

async function answerTheRequiredOnes(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole('radio', { name: 'Out regularly' }));
  await user.type(screen.getByLabelText(question('monthly-income').question), '2000');
  await user.type(screen.getByLabelText(question('balance').question), '5000.50');
}

describe('the rest of setup', () => {
  it('asks whatever the server says is still open, in its own words', async () => {
    renderApp('/setup/questions', signedIn());

    expect(await screen.findByRole('group', { name: question('lifestyle').question })).toBeInTheDocument();
    for (const choice of question('lifestyle').choices) {
      expect(screen.getByRole('radio', { name: choice.label })).toBeInTheDocument();
    }
    for (const q of questions.filter((q) => q.choices.length === 0)) {
      expect(screen.getByLabelText(q.question)).toBeInTheDocument();
    }
    expect(internalWordsIn(document.body.textContent ?? '')).toEqual([]);
  });

  it('says the repeated caveat once, above the questions it belongs to', async () => {
    renderApp('/setup/questions', signedIn());
    await screen.findByLabelText(question('spending:rent').question);

    // Ten spending questions all end "If you leave this, we assume the typical figure for Beirut…".
    expect(screen.getAllByText(/we assume the typical figure for Beirut/)).toHaveLength(1);
  });

  it('lets a question with a suggestion be left to it, and says what that means', async () => {
    renderApp('/setup/questions', signedIn());
    const rent = await screen.findByLabelText(question('spending:rent').question);

    expect(rent).toHaveValue('');
    expect(rent).toHaveAttribute('placeholder', question('spending:rent').suggested);
    expect(within(rent.closest('fieldset')!).getByText(/Leave it blank to use \$/)).toBeInTheDocument();
  });

  it('leaves what the server needs to the server, and shows its sentence when something is missing', async () => {
    // Whether a question may be skipped cannot be read off it - "already saving" has no suggestion
    // and is optional, income has none and is not - so the client does not guess. The server's
    // refusal (recorded from the live 409) says exactly what is missing.
    const needs = 'Tell us how much comes in each month, and how much you already have, first.';
    const server = new FakeServer().on('POST /api/v1/plan', { status: 409, body: { detail: needs } });
    renderApp('/setup/questions', signedIn(), server);
    const user = userEvent.setup();
    await screen.findByLabelText(question('monthly-income').question);
    await user.click(screen.getByRole('button', { name: 'Make my plan' }));

    expect(await screen.findByText(needs)).toBeInTheDocument();
    expect(screen.getByTestId('path')).toHaveTextContent('/setup/questions');
    // Nothing was typed, so nothing was sent as though it had been.
    expect(server.requests.filter((r) => r.method === 'PUT')).toEqual([]);
  });

  it('still checks the form of whatever is typed', async () => {
    const { server } = renderApp('/setup/questions', signedIn());
    const user = userEvent.setup();
    await user.type(await screen.findByLabelText(question('monthly-income').question), 'about 2000');
    await user.click(screen.getByRole('button', { name: 'Make my plan' }));

    expect(screen.getAllByRole('alert')[0]).toHaveTextContent(/Enter an amount in dollars/);
    expect(server.requests.filter((r) => r.method !== 'GET')).toEqual([]);
  });

  it('lets "already saving" stay empty, so a connected bank can answer it', async () => {
    // Sending 0 instead would be the user saying they save nothing, which outranks the bank.
    const { server } = renderApp('/setup/questions', signedIn());
    const user = userEvent.setup();
    await answerTheRequiredOnes(user);
    await user.click(screen.getByRole('button', { name: 'Make my plan' }));
    await screen.findByRole('heading', { name: 'Your plan' });

    expect(server.sentTo('PUT', '/api/v1/money')[0]).not.toHaveProperty('alreadySaving');
  });

  it('keeps a savings figure already on file when the money is sent again', async () => {
    // PUT /api/v1/money with alreadySaving left out *clears* it (checked against the live server).
    const onFile = { monthlyIncome: '1800.00', balance: '1000.00', setAside: '0.00', explanation: '', alreadySaving: '150.00', alreadySavingExplanation: '' };
    const server = new FakeServer().on('/api/v1/money', { status: 200, body: onFile });
    renderApp('/setup/questions', signedIn(), server);
    const user = userEvent.setup();
    await answerTheRequiredOnes(user);
    await user.click(screen.getByRole('button', { name: 'Make my plan' }));
    await screen.findByRole('heading', { name: 'Your plan' });

    expect(server.sentTo('PUT', '/api/v1/money')).toEqual([
      { monthlyIncome: '2000', balance: '5000.50', alreadySaving: '150.00' },
    ]);
  });

  it('sends spending as one complete map, keeping what the server already held', async () => {
    // The trap: PUT /api/v1/spending *replaces* the map. Verified against the live server - two
    // single-category PUTs leave only the second. So what is already saved has to travel again.
    const server = new FakeServer().on('/api/v1/spending', { status: 200, body: { transport: '90.00' } });
    renderApp('/setup/questions', signedIn(), server);
    const user = userEvent.setup();
    await answerTheRequiredOnes(user);
    await user.type(screen.getByLabelText(question('spending:rent').question), '500');
    await user.type(screen.getByLabelText(question('spending:groceries').question), '380.00');
    await user.click(screen.getByRole('button', { name: 'Make my plan' }));

    await screen.findByRole('heading', { name: 'Your plan' });
    expect(server.sentTo('PUT', '/api/v1/spending')).toEqual([
      { transport: '90.00', rent: '500', groceries: '380.00' },
    ]);
  });

  it('never sends a suggestion back as though the user had typed it', async () => {
    // A category left blank stays out of the body, so the plan says it assumed the local figure
    // rather than labelling our estimate "Your own figure".
    const { server } = renderApp('/setup/questions', signedIn());
    const user = userEvent.setup();
    await answerTheRequiredOnes(user);
    await user.click(screen.getByRole('button', { name: 'Make my plan' }));

    await screen.findByRole('heading', { name: 'Your plan' });
    expect(server.sentTo('PUT', '/api/v1/spending')).toEqual([]);
  });

  it('sends both money figures together, and the profile whole with the new answer in it', async () => {
    const { server } = renderApp('/setup/questions', signedIn());
    const user = userEvent.setup();
    await answerTheRequiredOnes(user);
    await user.click(screen.getByRole('button', { name: 'Make my plan' }));
    await screen.findByRole('heading', { name: 'Your plan' });

    expect(server.sentTo('PUT', '/api/v1/money')).toEqual([{ monthlyIncome: '2000', balance: '5000.50' }]);
    // The city came from the saved profile: answering how you live must not forget where you live.
    expect(server.sentTo('PUT', '/api/v1/profile')).toEqual([
      expect.objectContaining({ country: 'LB', city: 'beirut', lifestyle: 'regular' }),
    ]);
    expect(server.sentTo('POST', '/api/v1/plan')).toHaveLength(1);
  });

  it("shows the server's own sentence when it refuses an answer, and stays put", async () => {
    const server = new FakeServer().on('PUT /api/v1/money', {
      status: 400,
      body: { title: 'That does not look right', detail: 'Enter 0 if there is none.' },
    });
    renderApp('/setup/questions', signedIn(), server);
    const user = userEvent.setup();
    await answerTheRequiredOnes(user);
    await user.click(screen.getByRole('button', { name: 'Make my plan' }));

    expect(await screen.findByText('Enter 0 if there is none.')).toBeInTheDocument();
    expect(screen.getByTestId('path')).toHaveTextContent('/setup/questions');
    expect(server.sentTo('POST', '/api/v1/plan')).toEqual([]);
  });

  it('offers to make the plan straight away when nothing is left to ask', async () => {
    const server = new FakeServer().on('/api/v1/questions', { status: 200, body: [] });
    renderApp('/setup/questions', signedIn(), server);
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Make my plan' }));

    await screen.findByRole('heading', { name: 'Your plan' });
    expect(server.sentTo('POST', '/api/v1/plan')).toHaveLength(1);
  });
});
