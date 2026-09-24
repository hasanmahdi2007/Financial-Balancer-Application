import { useState, type FormEvent } from 'react';
import { Link } from 'react-router';
import { useApi } from '../api/ApiProvider';
import { plan, type PlanLine, type Rebalance, type RebalanceAsk } from '../api/plan';
import { useAction } from '../api/useAction';
import { useRemote } from '../api/useRemote';
import { WaysOut } from '../plan/PlanView';
import { orNothing } from '../setup/saveLocation';
import { AMOUNT_PROBLEM, formatMoney, isAmount } from '../ui/amount';
import { Failure, Loading } from '../ui/Feedback';
import { AnswerPlaceholder } from '../ui/AnswerPlaceholder';
import { MoneyField } from '../ui/MoneyField';

/**
 * "Give me more for this, and take it from the rest." A proposal only: nothing is saved, so someone
 * can ask as many times as they like and see what each would cost before deciding anything.
 */
export function RebalancePage() {
  const api = useApi();
  const latest = useRemote('plan', (signal) => orNothing(plan.latest(api, signal)));
  return (
    <section className="card">
      <h1>Give me more for something</h1>
      <p className="lede">
        Say what you want more for, and we will show you where it could come from. Nothing changes until you decide.
      </p>
      {latest.state === 'loading' ? <Loading what="your plan" /> : null}
      {latest.state === 'failed' ? <Failure message={latest.message} retry={latest.retry} /> : null}
      {latest.state === 'ready' ? (
        latest.data ? (
          <RebalanceForm lines={latest.data.surplus.lines} />
        ) : (
          <p>
            Make a plan first - this works from it. <Link to="/setup/questions">Finish setting up</Link>
          </p>
        )
      ) : null}
      <p>
        <Link to="/plan">Back to your plan</Link>
      </p>
    </section>
  );
}

function RebalanceForm({ lines }: { lines: PlanLine[] }) {
  const api = useApi();
  const [raise, setRaise] = useState(lines[0]?.id ?? '');
  const [by, setBy] = useState<'amount' | 'percent'>('amount');
  const [amount, setAmount] = useState('');
  const [percent, setPercent] = useState('');
  const [showProblems, setShowProblems] = useState(false);
  const [answer, setAnswer] = useState<Rebalance | null>(null);
  // A proposal answers one question; once the question changes it no longer applies.
  const edit =
    <T,>(set: (value: T) => void) =>
    (value: T) => {
      setAnswer(null);
      set(value);
    };

  const problem =
    by === 'amount'
      ? isAmount(amount) && !/^0+(\.0+)?$/.test(amount.trim())
        ? null
        : AMOUNT_PROBLEM
      : /^([1-9]\d?|100)$/.test(percent.trim())
        ? null
        : 'Enter a whole percentage from 1 to 100.';

  const ask = useAction(async (request: RebalanceAsk) => {
    setAnswer(null);
    setAnswer(await plan.rebalance(api, request));
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    if (problem) {
      setShowProblems(true);
      return;
    }
    void ask.run(
      by === 'amount' ? { raise, amount: amount.trim() } : { raise, percentOfBalance: Number(percent.trim()) },
    );
  }

  return (
    <div className="split">
      <form className="form" onSubmit={submit} noValidate>
        <label className="field">
          <span className="field__label">What do you want more for?</span>
          <select value={raise} onChange={(e) => edit(setRaise)(e.target.value)}>
            {lines.map((line) => (
              <option key={line.id} value={line.id}>
                {line.label}
              </option>
            ))}
          </select>
        </label>
        <fieldset className="question">
          <legend className="field__label">How much more?</legend>
          <div className="options">
            <label className="option">
              <input type="radio" name="by" checked={by === 'amount'} onChange={() => edit(setBy)('amount')} />
              <span>An amount each month</span>
            </label>
            <label className="option">
              <input type="radio" name="by" checked={by === 'percent'} onChange={() => edit(setBy)('percent')} />
              <span>A share of the savings this plan uses</span>
            </label>
          </div>
          {by === 'amount' ? (
            <MoneyField
              label="Amount"
              value={amount}
              onChange={edit(setAmount)}
              problem={showProblems ? problem : null}
            />
          ) : (
            <MoneyField
              label="Percentage"
              unit="%"
              unitAfter
              inputMode="numeric"
              value={percent}
              onChange={edit(setPercent)}
              problem={showProblems ? problem : null}
            />
          )}
        </fieldset>
        {ask.error ? <Failure message={ask.error} /> : null}
        <div className="actions">
          <button type="submit" className="button" disabled={ask.busy}>
            {ask.busy ? 'Working it out…' : 'Show me'}
          </button>
        </div>
      </form>
      {answer ? (
        <RebalanceAnswer answer={answer} />
      ) : (
        <AnswerPlaceholder icon="sliders" title="Where it could come from">
          Choose what you want more for and how much. Nothing changes until you decide.
        </AnswerPlaceholder>
      )}
    </div>
  );
}

export function RebalanceAnswer({ answer }: { answer: Rebalance }) {
  return (
    <section className="panel" aria-labelledby="rebalance-heading" aria-live="polite">
      <h2 id="rebalance-heading">{answer.outcome.label}</h2>
      <p>{answer.outcome.meaning}</p>
      <p className="aside">This is a suggestion. Nothing has been changed.</p>
      {answer.changes.length > 0 ? (
        <table className="lines">
          <thead>
            <tr>
              <th scope="col">What</th>
              <th scope="col">Now</th>
              <th scope="col">Would be</th>
              <th scope="col">Change</th>
            </tr>
          </thead>
          <tbody>
            {answer.changes.map((c) => (
              <tr key={c.id}>
                <th scope="row">{c.label}</th>
                <td>{formatMoney(c.from)}</td>
                <td>{formatMoney(c.to)}</td>
                <td>{c.by.startsWith('-') ? formatMoney(c.by) : `+${formatMoney(c.by)}`}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}
      {answer.stillShort !== '0.00' ? (
        <div className="banner banner--warning" role="note">
          <strong>
            We found {formatMoney(answer.granted)} of it. {formatMoney(answer.stillShort)} is still missing.
          </strong>
          {answer.options.length > 0 ? <WaysOut options={answer.options} /> : null}
        </div>
      ) : null}
      {answer.hints.length > 0 ? (
        <div className="hints">
          <h3>Where else things could move</h3>
          {/* Sentences, never figures: none of these is counted in what we found above. */}
          <ul>
            {answer.hints.map((h) => (
              <li key={h.id}>
                <strong>{h.label}:</strong> {h.hint}
              </li>
            ))}
          </ul>
        </div>
      ) : null}
    </section>
  );
}
