import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { FakeAuth, FakeServer, renderApp } from '../test/fakes';
import { internalWordsIn } from '../test/internalWords';
import affordBand from '../test/fixtures/afford-band.json';
import affordPriced from '../test/fixtures/afford-priced.json';
import rebalance from '../test/fixtures/rebalance.json';

const signedIn = () => new FakeAuth().signedInAs('maya@example.com');

describe('can I afford this?', () => {
  it('never gives a verdict without the cheaper option beside it, and that option’s own verdict', async () => {
    // The fixture is the awkward case: the cheaper meal is refused too. It is still shown - "no, and
    // this cheaper one would not fit either" is information; a bare "no" is only a scold.
    const { server } = renderApp('/afford', signedIn());
    const user = userEvent.setup();
    await user.click(await screen.findByRole('radio', { name: /^A normal meal out/ }));
    await user.type(screen.getByLabelText(/already spent on eating out/), '120.00');
    await user.click(screen.getByRole('button', { name: 'Check' }));

    const verdict = (await screen.findByRole('heading', { name: affordBand.verdict.label })).closest('section')!;
    const cheaper = within(verdict).getByRole('group', { name: 'A cheaper option' });
    expect(cheaper).toHaveTextContent(affordBand.cheaper!.label);
    expect(cheaper).toHaveTextContent('$4.50');
    expect(cheaper).toHaveTextContent(affordBand.cheaper!.verdict.label);
    expect(cheaper).toHaveTextContent(affordBand.cheaper!.verdict.meaning);
    expect(server.sentTo('POST', '/api/v1/decisions/afford')).toEqual([
      { category: 'dining-out', band: 'medium', spentThisMonth: '120.00' },
    ]);
    expect(internalWordsIn(document.body.textContent ?? '')).toEqual([]);
  });

  it('leaves out what was spent when it is left blank, rather than claiming nothing was', async () => {
    // Zero would make every answer look more affordable than it is. Left out, a connected bank
    // answers it - and without one, the server says what it needs.
    const server = new FakeServer().on('POST /api/v1/decisions/afford', {
      status: 409,
      body: { detail: 'Tell us how much you have already spent on eating out this month.' },
    });
    renderApp('/afford', signedIn(), server);
    const user = userEvent.setup();
    await user.click(await screen.findByRole('radio', { name: /^Fast food/ }));
    await user.click(screen.getByRole('button', { name: 'Check' }));

    expect(await screen.findByText('Tell us how much you have already spent on eating out this month.')).toBeInTheDocument();
    expect(server.sentTo('POST', '/api/v1/decisions/afford')).toEqual([{ category: 'dining-out', band: 'fast-food' }]);
  });

  it('prices anything else by what the user says it costs, and says when it runs into next month', async () => {
    const server = new FakeServer().on('POST /api/v1/decisions/afford', { status: 200, body: affordPriced });
    renderApp('/afford', signedIn(), server);
    const user = userEvent.setup();
    await user.selectOptions(await screen.findByLabelText('What is it for?'), 'clothing');
    await user.type(screen.getByLabelText('What is it?'), 'Winter jacket');
    await user.type(screen.getByLabelText('What does it cost?'), '200');
    await user.type(screen.getByLabelText(/already spent on clothes/), '0');
    await user.click(screen.getByRole('button', { name: 'Check' }));

    expect(await screen.findByRole('heading', { name: affordPriced.verdict.label })).toBeInTheDocument();
    expect(screen.getByText(/runs \$148\.28 into next month/)).toBeInTheDocument();
    expect(server.sentTo('POST', '/api/v1/decisions/afford')).toEqual([
      { category: 'clothing', label: 'Winter jacket', price: '200', spentThisMonth: '0' },
    ]);
  });

  it('says where the price came from', async () => {
    renderApp('/afford', signedIn());
    const user = userEvent.setup();
    await user.click(await screen.findByRole('radio', { name: /^A normal meal out/ }));
    await user.type(screen.getByLabelText(/already spent on eating out/), '120');
    await user.click(screen.getByRole('button', { name: 'Check' }));

    await screen.findByRole('heading', { name: affordBand.verdict.label });
    expect(screen.getAllByText(affordBand.purchase.basis.label).length).toBeGreaterThan(0);
    expect(screen.getByText(affordBand.purchase.basis.meaning)).toBeInTheDocument();
  });
});

describe('give me more for something', () => {
  it('shows where the money would come from, and says that nothing has changed', async () => {
    const { server } = renderApp('/rebalance', signedIn());
    const user = userEvent.setup();
    await user.selectOptions(await screen.findByLabelText('What do you want more for?'), 'dining-out');
    await user.type(screen.getByLabelText('Amount'), '50');
    await user.click(screen.getByRole('button', { name: 'Show me' }));

    expect(await screen.findByRole('heading', { name: rebalance.outcome.label })).toBeInTheDocument();
    expect(screen.getByText('This is a suggestion. Nothing has been changed.')).toBeInTheDocument();
    const entertainment = screen.getByRole('rowheader', { name: 'Going out and fun' }).closest('tr')!;
    expect(entertainment).toHaveTextContent('$120.00$70.00-$50.00');
    expect(server.sentTo('POST', '/api/v1/decisions/rebalance')).toEqual([{ raise: 'dining-out', amount: '50' }]);
  });

  it('keeps the hints as sentences, apart from anything counted', async () => {
    renderApp('/rebalance', signedIn());
    const user = userEvent.setup();
    await user.type(await screen.findByLabelText('Amount'), '50');
    await user.click(screen.getByRole('button', { name: 'Show me' }));
    await screen.findByRole('heading', { name: rebalance.outcome.label });

    const table = screen.getByRole('table');
    for (const hint of rebalance.hints) {
      expect(screen.getByText(hint.hint)).toBeInTheDocument();
      expect(table).not.toHaveTextContent(hint.hint);
    }
  });

  it('asks for a share of savings as a whole percentage', async () => {
    const { server } = renderApp('/rebalance', signedIn());
    const user = userEvent.setup();
    await user.click(await screen.findByRole('radio', { name: 'A share of the savings this plan uses' }));
    await user.type(screen.getByLabelText('Percentage'), '2.5');
    await user.click(screen.getByRole('button', { name: 'Show me' }));
    expect(screen.getByRole('alert')).toHaveTextContent('Enter a whole percentage from 1 to 100.');

    await user.clear(screen.getByLabelText('Percentage'));
    await user.type(screen.getByLabelText('Percentage'), '5');
    await user.click(screen.getByRole('button', { name: 'Show me' }));
    await screen.findByRole('heading', { name: rebalance.outcome.label });
    expect(server.sentTo('POST', '/api/v1/decisions/rebalance')).toEqual([
      { raise: expect.any(String), percentOfBalance: 5 },
    ]);
  });
});

describe('an answer on screen', () => {
  it('goes away once the question it answered changes', async () => {
    renderApp('/afford', signedIn());
    const user = userEvent.setup();
    await user.click(await screen.findByRole('radio', { name: /^A normal meal out/ }));
    await user.type(screen.getByLabelText(/already spent on eating out/), '120');
    await user.click(screen.getByRole('button', { name: 'Check' }));
    await screen.findByRole('heading', { name: affordBand.verdict.label });

    await user.click(screen.getByRole('radio', { name: /^Somewhere nicer/ }));
    expect(screen.queryByRole('heading', { name: affordBand.verdict.label })).not.toBeInTheDocument();
  });
});
