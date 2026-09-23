import { useState, type FormEvent } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { useApi } from '../api/ApiProvider';
import { catalogue, type SpendingQuestion } from '../api/catalogue';
import { useRemote } from '../api/useRemote';
import { Failure, Loading } from '../ui/Feedback';
import { useOnboardingDraft, type ManualAnswer } from './OnboardingDraft';
import { liftSharedEnding } from './sharedWording';

/** Whole dollars or dollars and cents, never negative. Kept as text so no amount becomes a float. */
const AMOUNT = /^\d{1,7}(\.\d{1,2})?$/;
const AMOUNT_PROBLEM = 'Enter an amount in dollars, like 350 or 350.50.';

export function ManualFormStep() {
  const { countryCode = '' } = useParams();
  const api = useApi();
  const countries = useRemote('countries', (signal) => catalogue.countries(api, signal));
  const questions = useRemote(countryCode, (signal) => catalogue.manualForm(api, countryCode, signal));
  const country = countries.state === 'ready' ? countries.data.find((c) => c.code === countryCode) : undefined;

  return (
    <section className="card">
      <p className="step">Step 1 · Where you live</p>
      <h1>Tell us about your city</h1>
      <p className="lede">
        We have filled in typical monthly figures{country ? ` for ${country.name}` : ''}. Change any that you know are
        different for you — your own numbers always win over ours, and they stay private to your account.
      </p>
      {/* No country-note banner here: the server already folds that caveat into every `why`. */}
      {questions.state === 'loading' ? <Loading what="the questions" /> : null}
      {questions.state === 'failed' ? <Failure message={questions.message} retry={questions.retry} /> : null}
      {questions.state === 'ready' ? (
        <ManualForm key={countryCode} countryCode={countryCode} questions={questions.data} />
      ) : null}
    </section>
  );
}

function initialAnswers(questions: SpendingQuestion[], saved: ManualAnswer[] | undefined): string[] {
  // Keep what the user already typed if they come back, but only answer-for-question: if the form
  // changed underneath them, a stale answer would land in the wrong box.
  return questions.map((q) => saved?.find((a) => a.question === q.question)?.amount ?? q.suggested);
}

function ManualForm({ countryCode, questions }: { countryCode: string; questions: SpendingQuestion[] }) {
  const navigate = useNavigate();
  const { draft, chooseLocation } = useOnboardingDraft();
  const previous =
    draft.location?.kind === 'unlisted' && draft.location.countryCode === countryCode ? draft.location : undefined;

  const [cityLabel, setCityLabel] = useState(previous?.cityLabel ?? '');
  const [amounts, setAmounts] = useState<string[]>(() => initialAnswers(questions, previous?.answers));
  const [showProblems, setShowProblems] = useState(false);

  const invalid = amounts.map((amount) => !AMOUNT.test(amount.trim()));
  const cityMissing = cityLabel.trim() === '';

  // The server builds every question's `why` and `basis` from the same parts, so on a ten-question
  // form the same caveat arrives ten times. Said once at the top, what differs per box is readable.
  const why = liftSharedEnding(questions.map((q) => q.why));
  const basis = liftSharedEnding(questions.map((q) => q.basis));

  function submit(event: FormEvent) {
    event.preventDefault();
    if (cityMissing || invalid.some(Boolean)) {
      setShowProblems(true);
      return;
    }
    chooseLocation({
      kind: 'unlisted',
      countryCode,
      cityLabel: cityLabel.trim(),
      answers: questions.map((q, i) => ({ question: q.question, amount: amounts[i]!.trim() })),
    });
    navigate('/setup/next');
  }

  return (
    <form className="form" onSubmit={submit} noValidate>
      {why.shared || basis.shared ? (
        <div className="banner banner--info" role="note">
          {why.shared ? <p>{why.shared}</p> : null}
          {basis.shared ? <p>{basis.shared}</p> : null}
        </div>
      ) : null}

      <label className="field">
        <span className="field__label">What is your city called?</span>
        <span className="field__help">
          We only use this to show your city's name back to you. We don't look it up, so spelling doesn't matter.
        </span>
        <input
          type="text"
          autoComplete="address-level2"
          value={cityLabel}
          onChange={(e) => setCityLabel(e.target.value)}
          aria-invalid={showProblems && cityMissing}
        />
        {showProblems && cityMissing ? (
          <span className="field__error" role="alert">
            Tell us your city's name so we can show it back to you.
          </span>
        ) : null}
      </label>

      {questions.map((q, i) => {
        const id = `amount-${i}`;
        const problem = showProblems && invalid[i];
        return (
          <fieldset className="question" key={q.question}>
            <label className="field" htmlFor={id}>
              <span className="field__label">{q.question}</span>
            </label>
            {why.particular[i] ? <p className="question__why">{why.particular[i]}</p> : null}
            <div className="money-input">
              <span aria-hidden="true">$</span>
              <input
                id={id}
                type="text"
                inputMode="decimal"
                value={amounts[i]}
                aria-invalid={problem}
                aria-describedby={basis.particular[i] ? `${id}-covers ${id}-basis` : `${id}-covers`}
                onChange={(e) => setAmounts((all) => all.map((a, j) => (j === i ? e.target.value : a)))}
              />
              <span className="money-input__unit">a month</span>
            </div>
            {problem ? (
              <span className="field__error" role="alert">
                {AMOUNT_PROBLEM}
              </span>
            ) : null}
            <div id={`${id}-covers`} className="question__covers">
              <span>This covers:</span>
              <ul>
                {q.covers.map((line) => (
                  <li key={line}>{line}</li>
                ))}
              </ul>
            </div>
            {basis.particular[i] ? (
              <p id={`${id}-basis`} className="question__basis">
                {basis.particular[i]}
              </p>
            ) : null}
          </fieldset>
        );
      })}

      {showProblems && (cityMissing || invalid.some(Boolean)) ? (
        <p className="field__error" role="alert">
          Some answers need another look before you can continue.
        </p>
      ) : null}
      <div className="actions">
        <Link to="/setup/location" className="button button--secondary">
          Back
        </Link>
        <button type="submit" className="button">
          Save and continue
        </button>
      </div>
    </form>
  );
}
