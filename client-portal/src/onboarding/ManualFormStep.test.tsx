import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { FakeAuth, FakeServer, renderApp } from '../test/fakes';
import { internalWordsIn } from '../test/internalWords';
import questions from '../test/fixtures/manual-form-LB.json';

const signedIn = () => new FakeAuth().signedInAs('maya@example.com');
const rent = questions[0]!;

const amountBoxes = () => screen.getAllByRole('textbox').slice(1);

async function fillCity(name: string) {
  const user = userEvent.setup();
  await user.type(await screen.findByLabelText(/What is your city called/), name);
  return user;
}

describe('the form for a city we do not hold figures for', () => {
  it('arrives already filled in, because eleven blank boxes is where people abandon signup', async () => {
    renderApp('/setup/LB/my-city', signedIn());

    await screen.findByLabelText(rent.question);
    expect(amountBoxes().map((box) => (box as HTMLInputElement).value)).toEqual(questions.map((q) => q.suggested));
  });

  it('explains every box in the words the server sent, so nothing has to be guessed at', async () => {
    renderApp('/setup/LB/my-city', signedIn());

    const box = (await screen.findByLabelText(rent.question)).closest('fieldset')!;
    for (const line of rent.covers) expect(within(box).getByText(line)).toBeInTheDocument();
    expect(internalWordsIn(document.body.textContent ?? '')).toEqual([]);
  });

  it('says the reason and the caveat once, not once per box', async () => {
    // The server assembles both from the same parts, so ten questions carry them ten times. Read
    // ten times over, they bury the one line per box that differs, and a form nobody reads is a
    // form people abandon.
    renderApp('/setup/LB/my-city', signedIn());
    await screen.findByLabelText(rent.question);

    const note = screen.getByRole('note');
    expect(note).toHaveTextContent('Prices have been quoted in US dollars');
    expect(note).toHaveTextContent('It is a starting point rather than a measurement of your life.');
    expect(screen.getAllByText(/starting point rather than a measurement/)).toHaveLength(1);
    expect(screen.getAllByText(/Prices have been quoted in US dollars/)).toHaveLength(1);
  });

  it('keeps the answers as typed and carries them forward with the city name', async () => {
    renderApp('/setup/LB/my-city', signedIn());
    const user = await fillCity('Zahlé');
    const first = amountBoxes()[0]!;
    await user.clear(first);
    await user.type(first, '410.50');
    await user.click(screen.getByRole('button', { name: 'Save and continue' }));

    expect(await screen.findByRole('heading', { name: 'Zahlé it is' })).toBeInTheDocument();
    const saved = JSON.parse(sessionStorage.getItem('fb.onboarding.draft')!).location;
    expect(saved.cityLabel).toBe('Zahlé');
    // A string, never a number: parsing money into a binary float is the representation the server
    // refuses to use.
    expect(saved.answers[0]).toEqual({
      category: rent.category,
      question: rent.question,
      amount: '410.50',
      suggested: rent.suggested,
    });
    expect(saved.answers).toHaveLength(questions.length);
  });

  it('refuses an amount that is not money, and says what one looks like', async () => {
    renderApp('/setup/LB/my-city', signedIn());
    const user = await fillCity('Zahlé');
    const first = amountBoxes()[0]!;
    await user.clear(first);
    await user.type(first, 'about 400');
    await user.click(screen.getByRole('button', { name: 'Save and continue' }));

    expect(screen.getAllByRole('alert')[0]).toHaveTextContent(/Enter an amount in dollars, like 350 or 350.50/);
    expect(screen.getByTestId('path')).toHaveTextContent('/setup/LB/my-city');
  });

  it('asks for the city name before going on, since the whole point is to show it back', async () => {
    renderApp('/setup/LB/my-city', signedIn());
    const user = userEvent.setup();
    await screen.findByLabelText(rent.question);
    await user.click(screen.getByRole('button', { name: 'Save and continue' }));

    expect(screen.getAllByRole('alert')[0]).toHaveTextContent("Tell us your city's name");
    expect(screen.getByTestId('path')).toHaveTextContent('/setup/LB/my-city');
  });

  it('says so when there is nothing to ask, instead of offering a form that saves nothing', async () => {
    // A country we hold no figures for answers with an empty list. Rendering the form anyway gives
    // the user a Save button that records no answers and a next screen claiming "0 figures".
    const server = new FakeServer().on('/api/catalogue/countries/LB/manual-form', { status: 200, body: [] });
    renderApp('/setup/LB/my-city', signedIn(), server);

    expect(await screen.findByRole('note')).toHaveTextContent('We have nothing to ask you about yet.');
    expect(screen.queryByRole('button', { name: 'Save and continue' })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Back' })).toBeInTheDocument();
  });

  it('gives back what was typed when the user comes back to change something', async () => {
    renderApp('/setup/LB/my-city', signedIn());
    const user = await fillCity('Zahlé');
    const first = amountBoxes()[0]!;
    await user.clear(first);
    await user.type(first, '410.50');
    await user.click(screen.getByRole('button', { name: 'Save and continue' }));
    await screen.findByRole('heading', { name: 'Zahlé it is' });

    await user.click(screen.getByRole('link', { name: 'Change where you live' }));
    await user.selectOptions(await screen.findByLabelText(/^City/), '__unlisted__');
    await user.click(await screen.findByRole('button', { name: 'Continue' }));

    expect(await screen.findByLabelText(/What is your city called/)).toHaveValue('Zahlé');
    expect((amountBoxes()[0] as HTMLInputElement).value).toBe('410.50');
  });
});
