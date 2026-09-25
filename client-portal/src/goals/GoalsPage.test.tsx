import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { FakeAuth, FakeServer, renderApp } from '../test/fakes';
import goalRemoved from '../test/fixtures/goal-removed.json';
import planBeforeToday from '../test/fixtures/plan-before-today.json';
import planGoals from '../test/fixtures/plan-goals.json';

const signedIn = () => new FakeAuth().signedInAs('maya@example.com');

/** A plan recorded from a live budget-core: a car a third covered, and a wedding not started. */
const withGoals = () => new FakeServer().on('/api/v1/plan', { status: 200, body: planGoals });

async function cards() {
  await screen.findByRole('heading', { name: 'Your goals' });
  return within(screen.getByRole('main')).findAllByRole('listitem');
}

describe('the goals page', () => {
  it('is in the menu', async () => {
    renderApp('/plan', signedIn(), withGoals());
    const menu = await screen.findByRole('navigation', { name: 'Main' });
    expect(within(menu).getByRole('link', { name: 'Your goals' })).toHaveAttribute('href', '/goals');
  });

  it('lists every goal in the order the plan funded them, each with how far it has got', async () => {
    renderApp('/goals', signedIn(), withGoals());
    const [car, wedding] = await cards();

    expect(within(car!).getByRole('heading', { name: 'Car' })).toBeInTheDocument();
    expect(within(car!).getByText('33%')).toBeInTheDocument();
    expect(within(car!).getByText('$3,000.00 of $9,000.00')).toBeInTheDocument();
    expect(within(car!).getByRole('img', { name: 'Covered by what you already have: 33%' })).toBeInTheDocument();

    expect(within(wedding!).getByRole('heading', { name: 'Wedding' })).toBeInTheDocument();
    expect(within(wedding!).getByText('0%')).toBeInTheDocument();
  });

  it('shows what each goal still needs, per month and in total, and says so in the plan’s words', async () => {
    renderApp('/goals', signedIn(), withGoals());
    const [car] = await cards();
    const goal = planGoals.goals[0]!;

    expect(within(car!).getByText(`${goal.status.label}.`)).toBeInTheDocument();
    expect(within(car!).getByText('$6,000.00')).toBeInTheDocument();
    expect(within(car!).getByText('Short each month by')).toBeInTheDocument();
    expect(within(car!).getByText('$131.75')).toBeInTheDocument();
  });

  it('takes the server’s figure for the bar, which is rounded down', async () => {
    // 99.6% covered: drawn to the nearest percent it would look finished. The server says 99.
    const almost = structuredClone(planGoals);
    almost.goals[0]!.fromBalance = '8964.00';
    almost.goals[0]!.percentCovered = 99;
    renderApp('/goals', signedIn(), new FakeServer().on('/api/v1/plan', { status: 200, body: almost }));
    const [car] = await cards();

    expect(within(car!).getByText('99%')).toBeInTheDocument();
    expect(within(car!).getByRole('img', { name: 'Covered by what you already have: 99%' })).toBeInTheDocument();
  });

  it('shows a plan made before the figure existed without inventing one', async () => {
    renderApp('/goals', signedIn(), new FakeServer().on('/api/v1/plan', { status: 200, body: planBeforeToday }));
    const [first] = await cards();

    expect(within(first!).queryByText(/^\d+%$/)).not.toBeInTheDocument();
  });

  it('removes a goal and shows the list again', async () => {
    const server = withGoals().on(`DELETE /api/v1/goals/${planGoals.goals[1]!.id}`, { status: 200, body: goalRemoved });
    renderApp('/goals', signedIn(), server);
    const user = userEvent.setup();
    const [, wedding] = await cards();

    await user.click(within(wedding!).getByRole('button', { name: 'Remove' }));

    expect(server.sentTo('DELETE', `/api/v1/goals/${planGoals.goals[1]!.id}`)).toHaveLength(1);
    expect(server.requests.filter((r) => r.method === 'GET' && r.path === '/api/v1/plan').length).toBeGreaterThan(1);
  });

  it('links each goal to where it can be changed', async () => {
    renderApp('/goals', signedIn(), withGoals());
    const [car] = await cards();
    expect(within(car!).getByRole('link', { name: 'Change' })).toHaveAttribute(
      'href',
      `/goals/${planGoals.goals[0]!.id}`,
    );
  });

  it('before any plan, lists the goals already added and says what is missing', async () => {
    const noPlanYet = new FakeServer()
      .on('/api/v1/plan', { status: 404, body: { detail: 'You have no plan yet.' } })
      .on('/api/v1/goals', {
        status: 200,
        body: [{ id: 'g1', name: 'Car', priority: { key: 'high', label: 'Very important' }, target: '9000.00', deadline: '2027-06-30', finishFirst: false }],
      });
    renderApp('/goals', signedIn(), noPlanYet);

    expect(await screen.findByRole('heading', { name: 'Waiting for your plan' })).toBeInTheDocument();
    expect(screen.getByText(/Car: \$9,000.00 by/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Finish setting up' })).toHaveAttribute('href', '/setup/questions');
  });

  it('with no goals at all, says how to add one', async () => {
    const empty = structuredClone(planGoals);
    empty.goals = [];
    renderApp('/goals', signedIn(), new FakeServer().on('/api/v1/plan', { status: 200, body: empty }).on('/api/v1/goals', { status: 200, body: [] }));

    expect(await screen.findByText(/You have not added a goal yet/)).toBeInTheDocument();
    expect(within(screen.getByRole('main')).getByRole('link', { name: 'Add a goal' })).toHaveAttribute('href', '/goals/new');
  });
});
