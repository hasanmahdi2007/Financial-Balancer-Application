import { useState, type FormEvent } from 'react';
import { Link } from 'react-router';
import { useApi } from '../api/ApiProvider';
import { plan, type AffordAsk, type Affordability, type Choices } from '../api/plan';
import { useAction } from '../api/useAction';
import { useRemote } from '../api/useRemote';
import { AMOUNT_PROBLEM, formatMoney, isAmount } from '../ui/amount';
import { Failure, Loading } from '../ui/Feedback';
import { MoneyField } from '../ui/MoneyField';

/**
 * The category the server prices by kind of meal. It refuses a kind of meal for anything else ("Kinds
 * of meal only apply to eating out"), so this is the one key the client has to know to offer them at
 * all. It is sent, never shown - the label next to it on screen is the server's.
 */
const PRICED_BY_KIND_OF_MEAL = 'dining-out';

/** "Can I afford this today?" - for a meal out by kind, or for anything by its price. */
export function AffordPage() {
  const api = useApi();
  const choices = useRemote('choices', (signal) => plan.choices(api, signal));
  return (
    <section className="card">
      <h1>Can I afford this?</h1>
      <p className="lede">We check it against what you have left for the rest of this month.</p>
      {choices.state === 'loading' ? <Loading what="your categories" /> : null}
      {choices.state === 'failed' ? <Failure message={choices.message} retry={choices.retry} /> : null}
      {choices.state === 'ready' ? <AffordForm choices={choices.data} /> : null}
      <p>
        <Link to="/plan">Back to your plan</Link>
      </p>
    </section>
  );
}

function AffordForm({ choices }: { choices: Choices }) {
  const api = useApi();
  const [category, setCategory] = useState(PRICED_BY_KIND_OF_MEAL);
  const [band, setBand] = useState('');
  const [label, setLabel] = useState('');
  const [price, setPrice] = useState('');
  const [spent, setSpent] = useState('');
  const [showProblems, setShowProblems] = useState(false);
  const [answer, setAnswer] = useState<Affordability | null>(null);
  // An answer belongs to the question that produced it. Left on screen after an input changes, a
  // "You can afford this" would sit beside an item it was never about.
  const edit =
    <T,>(set: (value: T) => void) =>
    (value: T) => {
      setAnswer(null);
      set(value);
    };

  const byKind = category === PRICED_BY_KIND_OF_MEAL;
  const categoryLabel = choices.categories.find((c) => c.key === category)?.label ?? '';
  const problems = {
    band: byKind && !choices.mealBands.some((b) => b.key === band) ? 'Pick the kind of meal.' : null,
    label: !byKind && label.trim() === '' ? 'Say what it is, so the answer can name it.' : null,
    price: !byKind && !isAmount(price) ? AMOUNT_PROBLEM : null,
    spent: spent.trim() !== '' && !isAmount(spent) ? AMOUNT_PROBLEM : null,
  };
  const anyProblem = Object.values(problems).some(Boolean);

  const ask = useAction(async (request: AffordAsk) => {
    setAnswer(null);
    setAnswer(await plan.afford(api, request));
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    if (anyProblem) {
      setShowProblems(true);
      return;
    }
    const request: AffordAsk = byKind ? { category, band } : { category, label: label.trim(), price: price.trim() };
    // Left out rather than sent as zero: without it a connected bank answers from real payments, and
    // without a bank the server says so - assuming nothing was spent would flatter every answer.
    if (spent.trim() !== '') request.spentThisMonth = spent.trim();
    void ask.run(request);
  }

  const error = (text: string | null) =>
    showProblems && text ? (
      <span className="field__error" role="alert">
        {text}
      </span>
    ) : null;

  return (
    <>
      <form className="form" onSubmit={submit} noValidate>
        <label className="field">
          <span className="field__label">What is it for?</span>
          <select value={category} onChange={(e) => edit(setCategory)(e.target.value)}>
            {choices.categories.map((c) => (
              <option key={c.key} value={c.key}>
                {c.label}
              </option>
            ))}
          </select>
        </label>
        {byKind ? (
          <fieldset className="question">
            <legend className="field__label">What kind?</legend>
            <div className="options">
              {choices.mealBands.map((b) => (
                <label key={b.key} className="option">
                  <input type="radio" name="band" value={b.key} checked={band === b.key} onChange={() => edit(setBand)(b.key)} />
                  <span>
                    <strong>{b.label}</strong> - {b.covers}
                  </span>
                </label>
              ))}
            </div>
            {error(problems.band)}
          </fieldset>
        ) : (
          <>
            <div className="field">
              <label className="field__label" htmlFor="afford-label">
                What is it?
              </label>
              <input id="afford-label" type="text" value={label} onChange={(e) => edit(setLabel)(e.target.value)} />
              {error(problems.label)}
            </div>
            <MoneyField
              label="What does it cost?"
              value={price}
              onChange={edit(setPrice)}
              problem={showProblems ? problems.price : null}
            />
          </>
        )}
        <MoneyField
          label={`How much have you already spent on ${categoryLabel.toLowerCase()} this month?`}
          help="Leave it blank if your bank is connected - we will work it out from there."
          value={spent}
          onChange={edit(setSpent)}
          problem={showProblems ? problems.spent : null}
        />
        {ask.error ? <Failure message={ask.error} /> : null}
        <div className="actions">
          <button type="submit" className="button" disabled={ask.busy}>
            {ask.busy ? 'Checking…' : 'Check'}
          </button>
        </div>
      </form>
      {answer ? <Verdict answer={answer} /> : null}
    </>
  );
}

/**
 * The answer. The cheaper option is part of the verdict, not an afterthought beneath it: told only
 * "no", someone has been scolded; told "no, but this would work", they have been helped. It is shown
 * whatever its own verdict is - a cheaper option that is also too much is still worth knowing.
 */
export function Verdict({ answer }: { answer: Affordability }) {
  const { verdict, purchase, cheaper, catchUp } = answer;
  return (
    <section className="panel verdict" aria-labelledby="verdict-heading" aria-live="polite">
      <h2 id="verdict-heading">{verdict.label}</h2>
      <p>{verdict.meaning}</p>
      {cheaper ? (
        <div className="cheaper" role="group" aria-label="A cheaper option">
          <p>
            <strong>Cheaper:</strong> {cheaper.label} ({cheaper.covers}), about {formatMoney(cheaper.price)}.
          </p>
          <p>
            <strong>{cheaper.verdict.label}.</strong> {cheaper.verdict.meaning}
          </p>
        </div>
      ) : null}
      <p>
        {purchase.label}: about {formatMoney(purchase.price)}{' '}
        <span className="provenance__badge">{purchase.basis.label}</span>
      </p>
      <p className="aside">{purchase.basis.meaning}</p>
      <dl className="figures">
        <dt>For {answer.category.label.toLowerCase()} this month</dt>
        <dd>{formatMoney(answer.allowance)}</dd>
        <dt>Already spent</dt>
        <dd>{formatMoney(answer.spentThisMonth)}</dd>
        <dt>Left</dt>
        <dd>{formatMoney(answer.left)}</dd>
        <dt>
          A day, for the {answer.daysLeft} {answer.daysLeft === 1 ? 'day' : 'days'} left
        </dt>
        <dd>{formatMoney(answer.perDay)}</dd>
      </dl>
      <p className="aside">{answer.allowanceBasis}</p>
      {catchUp ? (
        catchUp.fitsThisMonth ? (
          <p>
            If you buy it, you would have {formatMoney(catchUp.perDayAfter)} a day for the next {catchUp.days}{' '}
            {catchUp.days === 1 ? 'day' : 'days'} - {formatMoney(catchUp.lessPerDay)} a day less than now.
          </p>
        ) : (
          <p>
            If you buy it, it runs {formatMoney(catchUp.runsIntoNextMonth)} into next month&apos;s money.
          </p>
        )
      ) : null}
    </section>
  );
}
