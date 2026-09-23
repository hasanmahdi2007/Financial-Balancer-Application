import { fireEvent, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { FakeAuth, FakeServer, renderApp } from '../test/fakes';
import choices from '../test/fixtures/choices.json';
import goalWaiting from '../test/fixtures/goal-waiting.json';
import goals from '../test/fixtures/goals.json';

const signedIn = () => new FakeAuth().signedInAs('maya@example.com');

async function fillIn(user: ReturnType<typeof userEvent.setup>) {
  await user.type(await screen.findByLabelText('What are you saving for?'), 'Car');
  await user.type(screen.getByLabelText('How much will it cost?'), '12000');
  // Date inputs do not take typed text in jsdom the way a browser does; set the value directly.
  fireEvent.change(screen.getByLabelText('When do you want it by?'), { target: { value: '2027-06-30' } });
  await user.click(screen.getByRole('radio', { name: /^Very important/ }));
}

describe('adding a goal', () => {
  it('offers every priority in the words the server sent, with what each one means', async () => {
    renderApp('/goals/new', signedIn());
    for (const p of choices.priorities) {
      expect(await screen.findByRole('radio', { name: `${p.label} - ${p.covers}` })).toBeInTheDocument();
    }
  });

  it('sends the goal by its keys and opens the plan it recomputed', async () => {
    const { server } = renderApp('/goals/new', signedIn());
    const user = userEvent.setup();
    await fillIn(user);
    await user.click(screen.getByRole('button', { name: 'Add and replan' }));

    await screen.findByRole('heading', { name: 'Your plan' });
    expect(server.sentTo('POST', '/api/v1/goals')).toEqual([
      { name: 'Car', target: '12000', deadline: '2027-06-30', priority: 'high' },
    ]);
  });

  it('is sent once, however many times the button is pressed', async () => {
    // A double-click that added the goal twice would replan around a goal nobody meant to have.
    let release!: () => void;
    const held = new Promise<void>((resolve) => (release = resolve));
    const server = new FakeServer().on('POST /api/v1/goals', async () => {
      await held;
      return new Response(JSON.stringify(goalWaiting), { status: 201 });
    });
    renderApp('/goals/new', signedIn(), server);
    const user = userEvent.setup();
    await fillIn(user);
    const add = screen.getByRole('button', { name: 'Add and replan' });
    await user.click(add);
    await user.click(add);
    release();

    await screen.findByText('Your goal is saved.');
    expect(server.sentTo('POST', '/api/v1/goals')).toHaveLength(1);
  });

  it('says what is missing, in the server’s words, when there is no plan to show it in yet', async () => {
    const server = new FakeServer().on('POST /api/v1/goals', { status: 201, body: goalWaiting });
    renderApp('/goals/new', signedIn(), server);
    const user = userEvent.setup();
    await fillIn(user);
    await user.click(screen.getByRole('button', { name: 'Add and replan' }));

    expect(await screen.findByText(goalWaiting.waitingFor!)).toBeInTheDocument();
    expect(screen.getByTestId('path')).toHaveTextContent('/goals/new');
  });

  it('checks the answers before sending anything', async () => {
    const { server } = renderApp('/goals/new', signedIn());
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Add and replan' }));

    expect(screen.getAllByRole('alert').length).toBeGreaterThanOrEqual(4);
    expect(server.sentTo('POST', '/api/v1/goals')).toEqual([]);
  });
});

describe('changing a goal', () => {
  it('starts from the goal as it is, and saves it under its own id', async () => {
    const car = goals[0]!;
    const server = new FakeServer().on(`PUT /api/v1/goals/${car.id}`, { status: 200, body: goalWaiting });
    renderApp(`/goals/${car.id}`, signedIn(), server);
    const user = userEvent.setup();

    expect(await screen.findByLabelText('What are you saving for?')).toHaveValue(car.name);
    fireEvent.change(screen.getByLabelText('When do you want it by?'), { target: { value: '2027-12-31' } });
    await user.click(screen.getByRole('button', { name: 'Save and replan' }));

    await screen.findByText('Your goal is saved.');
    expect(server.sentTo('PUT', `/api/v1/goals/${car.id}`)).toEqual([
      { name: car.name, target: car.target, deadline: '2027-12-31', priority: car.priority.key },
    ]);
  });
});
