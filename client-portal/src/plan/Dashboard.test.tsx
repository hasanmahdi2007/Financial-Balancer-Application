import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { FakeAuth, FakeServer, renderApp } from '../test/fakes';
import type { Plan } from '../api/plan';
import { internalWordsIn } from '../test/internalWords';
import finishFirst from '../test/fixtures/finish-first.json';
import planBeforeToday from '../test/fixtures/plan-before-today.json';
import planFixture from '../test/fixtures/plan.json';
import tightBeforeToday from '../test/fixtures/tight-plan-before-today.json';
import tightHistory from '../test/fixtures/tight-plan-history.json';
import tightPlan from '../test/fixtures/tight-plan.json';

const signedIn = () => new FakeAuth().signedInAs('maya@example.com');

/** A plan where the money does not stretch, and a higher-priority goal has just taken it all. */
const stretched = () =>
  new FakeServer()
    .on('/api/v1/plan', { status: 200, body: tightPlan })
    .on('/api/v1/plan/history', { status: 200, body: tightHistory });

/**
 * Real plans recorded before "where you stand today" existed. Every plan saved before it shipped looks
 * like this for ever, so the layout they were shown with is still guarded - against these, not against
 * a current plan with the new field stripped out.
 */
const beforeToday = () => new FakeServer().on('/api/v1/plan', { status: 200, body: planBeforeToday });
const stretchedBeforeToday = () =>
  new FakeServer()
    .on('/api/v1/plan', { status: 200, body: tightBeforeToday })
    .on('/api/v1/plan/history', { status: 200, body: tightHistory });

const row = (label: string) => screen.getByRole('rowheader', { name: new RegExp(`^${label}`) }).closest('tr')!;

describe('the plan', () => {
  it('never shows the monthly figure without what it already assumes you change, on a plan saved before today', async () => {
    renderApp('/plan', signedIn(), beforeToday());
    const surplus = (await screen.findByRole('heading', { name: 'Each month for your goals' })).closest('section')!;

    expect(within(surplus).getByText('$520.00')).toBeInTheDocument();
    const assumes = within(surplus).getByRole('group', { name: 'What this already assumes' });
    expect(assumes).toHaveTextContent('$280.00 less a month');
    expect(assumes).toHaveTextContent('Groceries: $380.00 → $300.00');
    expect(assumes).toHaveTextContent(planBeforeToday.surplus.assumedReductionExplanation);
  });

  it('gives a line that cannot be cut a sentence about where it could move, never a figure, on a plan saved before today', async () => {
    renderApp('/plan', signedIn(), beforeToday());
    await screen.findByRole('heading', { name: 'What you spend' });

    for (const hint of planBeforeToday.hints) {
      const line = row(hint.label);
      expect(within(line).getByText(hint.hint)).toBeInTheDocument();
      // The hint is advice, not money: it must not be dressed as an amount anywhere it appears.
      expect(hint.hint).not.toMatch(/\$\d/);
    }
    expect(screen.queryByRole('heading', { name: 'What would have to change' })).not.toBeInTheDocument();
  });

  it('says where every figure came from, once each, with the date it was gathered', async () => {
    renderApp('/plan', signedIn());
    await screen.findByRole('heading', { name: 'Where these figures come from' });

    expect(screen.getAllByText('We gathered this from listings and local prices. It is a careful estimate, not an official statistic.')).toHaveLength(1);
    expect(screen.getByText('You told us this, so we use it and nothing overrides it.')).toBeInTheDocument();
    expect(screen.getByText('1 September 2026')).toBeInTheDocument();
    // A line nobody told us about says so, rather than passing our estimate off as theirs.
    expect(row('Healthcare')).toHaveTextContent('our estimate - you have not told us yours');
    expect(row('Rent')).not.toHaveTextContent('our estimate');
  });

  it('names the month a figure was read off the bank, instead of calling it our estimate', async () => {
    // Worded by the server ("what you spent in February 2026") - a figure the user can check against
    // a statement is the only kind they have reason to believe, so the month is shown, not a flag.
    const measured: Plan = structuredClone(planFixture);
    const healthcare = measured.surplus.lines.find((l) => l.id === 'healthcare')!;
    healthcare.measuredFrom = 'what you spent in August 2026';
    renderApp('/plan', signedIn(), new FakeServer().on('/api/v1/plan', { status: 200, body: measured }));
    await screen.findByRole('heading', { name: 'What you spend' });

    expect(row('Healthcare')).toHaveTextContent('(what you spent in August 2026)');
    expect(row('Healthcare')).not.toHaveTextContent('our estimate');
  });

  it('raises a staleness warning, once, when the server says figures are ageing', async () => {
    // Exactly the shape `PlanView.Basis` sends, with `Wording`'s own words for a stale figure. Every
    // recorded fixture is too new to be ageing, so this is the only place the shape is exercised -
    // and an earlier version of this test set a plain string here, which hid that the client typed
    // the field as one and would have thrown the moment a real figure aged.
    const stale = {
      label: 'Probably out of date',
      meaning: 'Prices have moved a lot since this was gathered. Your own figure would make the plan more accurate.',
    };
    const ageing: Plan = structuredClone(planFixture);
    ageing.surplus.lines[0]!.basis!.ageing = stale;
    ageing.surplus.lines[1]!.basis!.ageing = stale;
    renderApp('/plan', signedIn(), new FakeServer().on('/api/v1/plan', { status: 200, body: ageing }));

    const warnings = await screen.findAllByRole('note', { name: stale.label });
    expect(warnings).toHaveLength(1);
    expect(warnings[0]).toHaveTextContent(stale.meaning);
  });

  it('never shows suggested cuts without what was already assumed and the total change, on a plan saved before today', async () => {
    renderApp('/plan', signedIn(), stretchedBeforeToday());
    const cuts = (await screen.findByRole('heading', { name: 'What would have to change' })).closest('section')!;

    expect(within(cuts).getByText(/Subscriptions: \$40\.00 less a month/)).toBeInTheDocument();
    expect(within(cuts).getByText('Already assumed').nextElementSibling).toHaveTextContent('$280.00');
    expect(within(cuts).getByText('Suggested on top').nextElementSibling).toHaveTextContent('$40.00');
    expect(within(cuts).getByText('Everything you would change').nextElementSibling).toHaveTextContent('$320.00');
  });

  it('when the numbers will not close, says by how much and offers the real ways out', async () => {
    renderApp('/plan', signedIn(), stretched());
    const note = await screen.findByText(/you are still \$1,906\.68 a month short/);
    const banner = note.closest('[role="note"]') as HTMLElement;

    for (const option of tightPlan.cuts.options) {
      expect(within(banner).getByText(option.meaning)).toBeInTheDocument();
    }
    expect(within(banner).getByRole('link', { name: 'Use more of your savings this month' })).toHaveAttribute('href', '/money');
  });

  it('explains a goal that lost its money to a more important one, instead of just showing it at zero', async () => {
    renderApp('/plan', signedIn(), stretched());
    const changed = await screen.findByRole('complementary', { name: 'What changed' });

    expect(changed).toHaveTextContent('You added a goal: Emergency fund');
    expect(changed).toHaveTextContent('Car: Behind, $220.00 a month and $1,000.00 from savings → Not funded yet, $0.00 a month and $0.00 from savings');
  });

  it('lists goals in the order they were paid, each with its status in words', async () => {
    renderApp('/plan', signedIn(), stretched());
    const goals = await screen.findAllByRole('heading', { level: 3 });
    const names = goals.map((g) => g.textContent).filter((n) => tightPlan.goals.some((g) => g.name === n));

    expect(names).toEqual(tightPlan.goals.map((g) => g.name));
    for (const goal of tightPlan.goals) expect(screen.getByText(goal.status.meaning)).toBeInTheDocument();
  });

  it('does not describe a different plan than the one on screen', async () => {
    // If the history has moved on (or lags behind), its newest entry is not this plan's story.
    const otherHistory = structuredClone(tightHistory);
    otherHistory[0]!.id = 'some-other-snapshot';
    renderApp('/plan', signedIn(), stretched().on('/api/v1/plan/history', { status: 200, body: otherHistory }));
    await screen.findByRole('heading', { name: 'Your goals' });

    expect(screen.queryByRole('complementary', { name: 'What changed' })).not.toBeInTheDocument();
  });

  it('lets a goal take the savings first, and shows the plan the server made from that', async () => {
    const server = stretched().on('PUT /api/v1/goals/finish-first', { status: 200, body: finishFirst });
    renderApp('/plan', signedIn(), server);
    const user = userEvent.setup();
    const car = tightPlan.goals.find((g) => g.name === 'Car')!;
    const carCard = (await screen.findByRole('heading', { name: 'Car' })).closest('li')!;
    await user.click(within(carCard).getByRole('button', { name: 'Give this my savings first' }));

    expect(server.sentTo('PUT', '/api/v1/goals/finish-first')).toEqual([{ goalId: car.id }]);
    // The plan is read again rather than patched on the client, so what is shown is what was saved.
    await screen.findByRole('heading', { name: 'Your goals' });
    expect(server.requests.filter((r) => r.method === 'GET' && r.path === '/api/v1/plan').length).toBeGreaterThan(1);
  });

  it('sends someone without a plan to setup, rather than showing an empty dashboard', async () => {
    renderApp('/plan', signedIn(), new FakeServer().on('/api/v1/plan', { status: 404 }));

    expect(await screen.findByRole('heading', { name: 'You do not have a plan yet' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Get started' })).toHaveAttribute('href', '/setup/location');
  });

  it('never shows an internal name', async () => {
    renderApp('/plan', signedIn(), stretched());
    await screen.findByRole('heading', { name: 'What you spend' });
    expect(internalWordsIn(document.body.textContent ?? '')).toEqual([]);
  });
});

describe('a past plan', () => {
  it('is shown as a record, with nothing on it that could change it', async () => {
    const id = tightHistory[1]!.id;
    renderApp(`/plan/history/${id}`, signedIn(), new FakeServer().on(`/api/v1/plan/history/${id}`, { status: 200, body: tightPlan }));
    await screen.findByRole('heading', { name: 'Your goals' });

    // Inside the page, that is: the header's Sign out is not part of the plan.
    const page = screen.getByRole('main');
    expect(within(page).queryByRole('button')).toBeNull();
    expect(within(page).queryByRole('link', { name: 'Change these' })).toBeNull();
  });

  it('is listed with the reason it was made and what it changed', async () => {
    renderApp('/plan/history', signedIn(), stretched());

    expect(await screen.findByRole('link', { name: 'You added a goal: Emergency fund' })).toBeInTheDocument();
    expect(screen.getByText('Your first plan.')).toBeInTheDocument();
    expect(screen.getAllByRole('listitem').some((li) => li.textContent?.startsWith('Car: Behind'))).toBe(true);
  });
});

describe('where you stand, then how to improve it', () => {
  /** True when `first` comes before `second` in the page, which is the order a person reads them in. */
  const before = (first: Element, second: Element) =>
    (first.compareDocumentPosition(second) & Node.DOCUMENT_POSITION_FOLLOWING) !== 0;
  const section = async (heading: string) =>
    (await screen.findByRole('heading', { name: heading, level: 2 })).closest('section')!;

  it('opens on what is really left from the figures as entered, before anything else', async () => {
    renderApp('/plan', signedIn());
    const today = await section('Where you stand today');

    const headings = screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent);
    expect(headings.slice(0, 2)).toEqual(['Where you stand today', 'How to improve it']);

    expect(within(today).getByText('Arrives each month').nextElementSibling).toHaveTextContent('$2,000.00');
    expect(within(today).getByText('What you spend').nextElementSibling).toHaveTextContent('$1,760.00');
    expect(within(today).getByText('Left each month').nextElementSibling).toHaveTextContent('$240.00');
    expect(today).toHaveTextContent(planFixture.today!.leftAsEnteredExplanation);
    // The card claims to be from what the user entered, so it says how much of it is our estimate.
    expect(today).toHaveTextContent(planFixture.today!.estimatedNote!);
    // The plan's own figure is not the first thing they read.
    expect(within(today).queryByText('$520.00')).not.toBeInTheDocument();
  });

  it('states each change as what it adds a month, and closes on the plan figure as their result', async () => {
    renderApp('/plan', signedIn());
    const improve = await section('How to improve it');

    const tips = within(improve).getByRole('list', { name: 'Changes and what each adds' });
    expect(tips).toHaveTextContent('Bring Groceries from $380.00 to $300.00: +$80.00 a month');
    expect(tips).toHaveTextContent('+$200.00 a month');

    const result = within(improve).getByRole('group', { name: 'The result' });
    expect(result).toHaveTextContent(planFixture.today!.planResult);
    expect(within(result).getByText('$520.00')).toBeInTheDocument();
    // Never the figure without what it counts on: the changes above it, and the sentence saying so.
    expect(result).toHaveTextContent(planFixture.surplus.assumedReductionExplanation);
    expect(within(result).getByText('Everything you would change').nextElementSibling).toHaveTextContent('$280.00');
    expect(before(tips, result)).toBe(true);
  });

  it('says a line that cannot be cut is advice in words, once, and never a figure', async () => {
    renderApp('/plan', signedIn());
    const improve = await section('How to improve it');

    for (const hint of planFixture.hints) {
      expect(within(improve).getByText(hint.hint)).toBeInTheDocument();
      expect(screen.getAllByText(hint.hint)).toHaveLength(1);
      expect(hint.hint).not.toMatch(/\$\d/);
    }
  });

  it('tells someone spending more than they earn that first, with how long their savings cover it', async () => {
    renderApp('/plan', signedIn(), stretched());
    const today = await section('Where you stand today');

    expect(within(today).getByText('Left each month').nextElementSibling).toHaveTextContent('-$60.00');
    expect(today).toHaveTextContent('You spend $60.00 more than you earn each month.');
    expect(today).toHaveTextContent('What you have would cover it for about 16 months.');
    // The plan's own runway is measured after its changes and would say the opposite; it is not here.
    expect(today).not.toHaveTextContent(tightPlan.money.runway.label);

    const improve = await section('How to improve it');
    expect(improve).toHaveTextContent('Spend less on Subscriptions: +$40.00 a month');
    expect(within(improve).getByText('Everything you would change').nextElementSibling).toHaveTextContent('$320.00');
  });

  it('shows a plan saved before this existed without the panel, and never works it out afresh', async () => {
    renderApp('/plan', signedIn(), beforeToday());
    await screen.findByRole('heading', { name: 'Each month for your goals' });

    expect(screen.queryByRole('heading', { name: 'Where you stand today' })).not.toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'How to improve it' })).not.toBeInTheDocument();
    // $240.00 is what the lines of this old plan would add up to. It was never said to the user, so
    // it must not appear: a past plan is a record of what they were told.
    expect(screen.queryByText('$240.00')).not.toBeInTheDocument();
  });
});
