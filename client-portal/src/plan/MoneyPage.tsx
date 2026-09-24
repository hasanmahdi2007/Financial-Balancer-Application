import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router';
import { useApi } from '../api/ApiProvider';
import { plan, type Money } from '../api/plan';
import { useAction } from '../api/useAction';
import { useRemote } from '../api/useRemote';
import { orNothing } from '../setup/saveLocation';
import { AMOUNT_PROBLEM, isAmount } from '../ui/amount';
import { Failure, Loading } from '../ui/Feedback';
import { MoneyField } from '../ui/MoneyField';

/**
 * What arrives each month and how much of their savings the plan may use.
 *
 * Both are sent together, always: the route refuses one without the other. Lowering the savings
 * figure is how someone takes money back out of the plan, and the next plan hands it back.
 */
export function MoneyPage() {
  const api = useApi();
  const money = useRemote('money', (signal) => orNothing(plan.money(api, signal)));
  return (
    <section className="card">
      <h1>Your money</h1>
      <p className="aside">
        Rather not type these? <Link to="/bank">Connect your bank</Link> and your plan uses what it really shows.
      </p>
      {money.state === 'loading' ? <Loading what="your figures" /> : null}
      {money.state === 'failed' ? <Failure message={money.message} retry={money.retry} /> : null}
      {money.state === 'ready' ? <MoneyForm existing={money.data} /> : null}
    </section>
  );
}

function MoneyForm({ existing }: { existing: Money | null }) {
  const api = useApi();
  const navigate = useNavigate();
  const [income, setIncome] = useState(existing?.monthlyIncome ?? '');
  const [balance, setBalance] = useState(existing?.balance ?? '');
  const [saving, setSaving] = useState(existing?.alreadySaving ?? '');
  const [showProblems, setShowProblems] = useState(false);
  const bad = {
    income: !isAmount(income),
    balance: !isAmount(balance),
    saving: saving.trim() !== '' && !isAmount(saving),
  };

  const save = useAction(async () => {
    // Empty means "let a connected bank say", and is sent that way - left out. Anything typed is
    // sent, including 0, which is the user saying they save nothing. Leaving it out when they had
    // not emptied the box would clear a figure they never asked to lose.
    await plan.saveMoney(api, {
      monthlyIncome: income.trim(),
      balance: balance.trim(),
      ...(saving.trim() === '' ? {} : { alreadySaving: saving.trim() }),
    });
    await plan.recompute(api);
    navigate('/plan');
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    if (bad.income || bad.balance || bad.saving) {
      setShowProblems(true);
      return;
    }
    void save.run();
  }

  return (
    <form className="form" onSubmit={submit} noValidate>
      {existing ? <p className="aside">{existing.explanation}</p> : null}
      <MoneyField
        label="How much arrives in your account each month?"
        help="What actually lands, after tax and deductions."
        value={income}
        onChange={setIncome}
        problem={showProblems && bad.income ? AMOUNT_PROBLEM : null}
      />
      <MoneyField
        label="How much of your savings may the plan use?"
        help="Only this much is used, and it goes toward your goals first. Lower it at any time to take money back out."
        value={balance}
        onChange={setBalance}
        problem={showProblems && bad.balance ? AMOUNT_PROBLEM : null}
      />
      <MoneyField
        label="How much do you already move into savings each month?"
        help={existing?.alreadySavingExplanation ?? 'Leave it empty if you would rather your bank told us.'}
        value={saving}
        onChange={setSaving}
        problem={showProblems && bad.saving ? AMOUNT_PROBLEM : null}
      />
      {save.error ? (
        <p className="field__error" role="alert">
          {save.error}
        </p>
      ) : null}
      <div className="actions">
        <Link to="/plan" className="button button--secondary">
          Cancel
        </Link>
        <button type="submit" className="button" disabled={save.busy}>
          {save.busy ? 'Working out your plan…' : 'Save and replan'}
        </button>
      </div>
    </form>
  );
}
