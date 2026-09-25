import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import type { PlanChoice, PlanChoices } from '../api/plans';
import { FakeAuth, FakeServer, renderApp } from '../test/fakes';
import planStarted from '../test/fixtures/plan-started.json';
import planUsed from '../test/fixtures/plan-used.json';
import plansAll from '../test/fixtures/plans-all.json';
import plansLB from '../test/fixtures/plans-LB.json';
import plansNoneUS from '../test/fixtures/plans-none-US.json';

const signedIn = () => new FakeAuth().signedInAs('maya@example.com');

/** The routes this screen uses, answered with payloads recorded from a live budget-core. */
function server() {
  return new FakeServer()
    .on('/api/v1/plans?country=US', { status: 200, body: plansNoneUS })
    .on('/api/v1/plans?country=LB', { status: 200, body: plansLB })
    .on('/api/v1/plans', { status: 200, body: plansAll })
    .on('POST /api/v1/plans', { status: 201, body: planStarted })
    .on('PUT /api/v1/plans/active', { status: 200, body: planUsed });
}

async function chooseCountry(user: ReturnType<typeof userEvent.setup>, name: string) {
  await user.selectOptions(await screen.findByLabelText(/^Country/), name);
}

describe('the recorded payloads', () => {
  it('are the shapes this client types them as', () => {
    // Compile-time: these assignments fail the typecheck if the server's shape drifts from ours.
    const all: PlanChoices = plansAll;
    const started: PlanChoice = planStarted;
    // Most recently used first: Austin was just started when this was recorded.
    expect(all.plans.map((p) => p.place.label)).toEqual(['Austin, United States', 'Beirut, Lebanon']);
    expect(started.active).toBe(true);
    expect(started.summary).toBeNull();
  });
});

describe('the New plan button', () => {
  it('is on the dashboard itself, and in the menu beside My plans', async () => {
    renderApp('/plan', signedIn(), server());
    const page = await screen.findByRole('main');
    const button = await within(page).findByRole('link', { name: 'New plan' });
    expect(button).toHaveAttribute('href', '/plans/new');

    const menu = screen.getByRole('navigation', { name: 'Main' });
    expect(within(menu).getByRole('link', { name: 'New plan' })).toHaveAttribute('href', '/plans/new');
    expect(within(menu).getByRole('link', { name: 'My plans' })).toHaveAttribute('href', '/plans');
  });
});

describe('starting a new plan', () => {
  it('in a country with no plan yet: picks a city, brings the goals, and goes on to the questions', async () => {
    const { server: fake } = renderApp('/plans/new', signedIn(), server());
    const user = userEvent.setup();

    await chooseCountry(user, 'United States');
    await user.selectOptions(await screen.findByLabelText(/^City/), 'austin');
    expect(screen.getByRole('checkbox', { name: 'Bring my goals with me' })).toBeChecked();
    await user.click(screen.getByRole('button', { name: 'Start a new plan here' }));

    expect(await screen.findByTestId('path')).toHaveTextContent('/setup/questions');
    expect(fake.sentTo('POST', '/api/v1/plans')).toEqual([
      { country: 'US', city: 'austin', cityNotListed: null, bringGoals: true },
    ]);
  });

  it('leaves the goals behind when the box is unticked', async () => {
    const { server: fake } = renderApp('/plans/new', signedIn(), server());
    const user = userEvent.setup();

    await chooseCountry(user, 'United States');
    await user.selectOptions(await screen.findByLabelText(/^City/), 'austin');
    await user.click(screen.getByRole('checkbox', { name: 'Bring my goals with me' }));
    await user.click(screen.getByRole('button', { name: 'Start a new plan here' }));

    await screen.findByText('/setup/questions');
    expect(fake.sentTo('POST', '/api/v1/plans')).toEqual([
      { country: 'US', city: 'austin', cityNotListed: null, bringGoals: false },
    ]);
  });

  it('for a city we do not list: sends the name as typed, and waits until there is one', async () => {
    const { server: fake } = renderApp('/plans/new', signedIn(), server());
    const user = userEvent.setup();

    await chooseCountry(user, 'United States');
    await user.selectOptions(await screen.findByLabelText(/^City/), "My city isn't listed");
    const start = screen.getByRole('button', { name: 'Start a new plan here' });
    expect(start).toBeDisabled();

    await user.type(screen.getByLabelText(/^What is your city called/), '  Boise ');
    await user.click(start);

    await screen.findByText('/setup/questions');
    expect(fake.sentTo('POST', '/api/v1/plans')).toEqual([
      { country: 'US', city: null, cityNotListed: 'Boise', bringGoals: true },
    ]);
  });

  it('is sent once, however many times the button is pressed', async () => {
    const { server: fake } = renderApp('/plans/new', signedIn(), server());
    const user = userEvent.setup();

    await chooseCountry(user, 'United States');
    await user.selectOptions(await screen.findByLabelText(/^City/), 'austin');
    const start = screen.getByRole('button', { name: 'Start a new plan here' });
    await user.dblClick(start);

    await screen.findByText('/setup/questions');
    expect(fake.sentTo('POST', '/api/v1/plans')).toHaveLength(1);
  });

  it('says in words why the server refused it, and stays on the page', async () => {
    const refusing = server().on('POST /api/v1/plans', {
      status: 400,
      body: { detail: 'We do not hold figures for FR yet. Choose a country from the list.' },
    });
    renderApp('/plans/new', signedIn(), refusing);
    const user = userEvent.setup();

    await chooseCountry(user, 'United States');
    await user.selectOptions(await screen.findByLabelText(/^City/), 'austin');
    await user.click(screen.getByRole('button', { name: 'Start a new plan here' }));

    expect(await screen.findByText(/Choose a country from the list/)).toBeInTheDocument();
    expect(screen.getByTestId('path')).toHaveTextContent('/plans/new');
  });
});

describe('a country that already has a plan', () => {
  it('offers that plan first, with what it last showed', async () => {
    renderApp('/plans/new', signedIn(), server());
    const user = userEvent.setup();

    await chooseCountry(user, 'Lebanon');

    const existing = await within(screen.getByRole('main')).findByRole('listitem');
    expect(within(existing).getByRole('heading', { name: 'Beirut, Lebanon' })).toBeInTheDocument();
    expect(within(existing).getByText('$305.00')).toBeInTheDocument();
    expect(within(existing).getByText('1 goal')).toBeInTheDocument();
    expect(within(existing).getByText(/come back exactly as you left them/)).toBeInTheDocument();
    // Not straight into a duplicate: the city picker waits until they choose to start a new one.
    expect(screen.queryByLabelText(/^City/)).not.toBeInTheDocument();
  });

  it('picks it up and opens it on the dashboard', async () => {
    const { server: fake } = renderApp('/plans/new', signedIn(), server());
    const user = userEvent.setup();

    await chooseCountry(user, 'Lebanon');
    await user.click(await screen.findByRole('button', { name: 'Use this plan' }));

    expect(await screen.findByTestId('path')).toHaveTextContent('/plan');
    expect(fake.sentTo('PUT', '/api/v1/plans/active')).toEqual([{ planId: plansLB.plans[0]!.id }]);
  });

  it('still lets them start a new one there', async () => {
    const { server: fake } = renderApp('/plans/new', signedIn(), server());
    const user = userEvent.setup();

    await chooseCountry(user, 'Lebanon');
    await user.click(await screen.findByRole('button', { name: 'Start a new plan here' }));
    await user.selectOptions(await screen.findByLabelText(/^City/), 'tripoli-lb');
    await user.click(screen.getByRole('button', { name: 'Start a new plan here' }));

    await screen.findByText('/setup/questions');
    expect(fake.sentTo('POST', '/api/v1/plans')).toEqual([
      { country: 'LB', city: 'tripoli-lb', cityNotListed: null, bringGoals: true },
    ]);
  });
});

describe('My plans', () => {
  it('lists every plan, marks the one in use, and picks up another', async () => {
    const { server: fake } = renderApp('/plans', signedIn(), server());
    const user = userEvent.setup();

    await screen.findByRole('heading', { name: 'My plans' });
    const plans = await within(screen.getByRole('main')).findAllByRole('listitem');
    expect(plans.map((p) => within(p).getByRole('heading').textContent)).toEqual([
      'Austin, United States',
      'Beirut, Lebanon',
    ]);
    const inUse = plans.find((p) => within(p).queryByText('The plan you are using'));
    expect(inUse).toBeDefined();
    expect(within(inUse!).queryByRole('button')).not.toBeInTheDocument();

    const other = plans.find((p) => within(p).queryByRole('button', { name: 'Use this plan' }))!;
    await user.click(within(other).getByRole('button', { name: 'Use this plan' }));

    expect(await screen.findByTestId('path')).toHaveTextContent('/plan');
    expect(fake.sentTo('PUT', '/api/v1/plans/active')).toHaveLength(1);
  });

  it('says a plan was never finished rather than showing empty figures', async () => {
    renderApp('/plans', signedIn(), server());
    await screen.findByRole('heading', { name: 'My plans' });
    const austin = (await within(screen.getByRole('main')).findAllByRole('listitem')).find((p) =>
      within(p).queryByRole('heading', { name: 'Austin, United States' }),
    )!;
    expect(within(austin).getByText(/Not finished yet/)).toBeInTheDocument();
  });
});
