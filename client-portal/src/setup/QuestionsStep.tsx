import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router';
import { useApi } from '../api/ApiProvider';
import type { ApiClient } from '../api/http';
import { plan, type OpenQuestion, type Profile, type ProfileEdit } from '../api/plan';
import { useAction } from '../api/useAction';
import { useRemote } from '../api/useRemote';
import { liftSharedEnding } from '../onboarding/sharedWording';
import { AMOUNT_PROBLEM, formatMoney, isAmount } from '../ui/amount';
import { Failure, Loading } from '../ui/Feedback';
import { groupByTarget, isChoice, parseTarget, type Answer } from './answers';
import { orNothing } from './saveLocation';

/**
 * The rest of setup: income, what the plan may use, how the user lives, and what they spend.
 *
 * Which of those are asked, in what order and in what words, is entirely the server's: this screen
 * renders whatever `GET /api/v1/questions` says is still open, and sends each answer where that
 * question's `answerWith` says it goes. Nothing here knows that a question called "lifestyle" exists.
 */
export function QuestionsStep() {
  const api = useApi();
  const questions = useRemote('questions', (signal) => plan.questions(api, signal));

  return (
    <section className="card">
      <p className="step">Step 2 · Your money</p>
      <h1>A few questions about how you live</h1>
      <p className="lede">
        Each answer shapes your plan. Where we can suggest a figure we do, and you can leave those blank to use it.
      </p>
      {questions.state === 'loading' ? <Loading what="your questions" /> : null}
      {questions.state === 'failed' ? <Failure message={questions.message} retry={questions.retry} /> : null}
      {questions.state === 'ready' ? (
        questions.data.length === 0 ? (
          <NothingLeft />
        ) : (
          <QuestionsForm key={questions.data.map((q) => q.key).join('|')} questions={questions.data} />
        )
      ) : null}
    </section>
  );
}

/** Makes the plan and opens it: the step every path through setup ends on. */
function useMakePlan() {
  const api = useApi();
  const navigate = useNavigate();
  return useAction(async (before?: () => Promise<void>) => {
    if (before) await before();
    await plan.recompute(api);
    navigate('/plan');
  });
}

function NothingLeft() {
  const make = useMakePlan();
  return (
    <>
      <p>You have told us everything we need.</p>
      {make.error ? (
        <p className="field__error" role="alert">
          {make.error}
        </p>
      ) : null}
      <button type="button" className="button" disabled={make.busy} onClick={() => void make.run()}>
        {make.busy ? 'Making your plan…' : 'Make my plan'}
      </button>
    </>
  );
}

/**
 * Only the form of an answer is checked here, never whether one was needed. Which questions may be
 * left empty is the server's call, and it cannot be read off the question: "How much do you already
 * put into savings?" has no suggestion and is still optional (empty lets a bank answer it), while
 * income has none and is not. An empty answer is left out, and if the plan truly needs it the server
 * refuses with a sentence saying so, which is shown as-is.
 */
function problemWith(question: OpenQuestion, value: string): string | null {
  const given = value.trim();
  if (given === '') return null;
  if (isChoice(question)) {
    return question.choices.some((c) => c.key === given) ? null : 'Pick the one that fits you best.';
  }
  return isAmount(given) ? null : AMOUNT_PROBLEM;
}

function QuestionsForm({ questions }: { questions: OpenQuestion[] }) {
  const api = useApi();
  const make = useMakePlan();
  const [values, setValues] = useState<Record<string, string>>(() =>
    Object.fromEntries(questions.map((q) => [q.key, ''])),
  );
  const [showProblems, setShowProblems] = useState(false);
  const problems = Object.fromEntries(questions.map((q) => [q.key, problemWith(q, values[q.key] ?? '')]));
  const anyProblem = Object.values(problems).some(Boolean);

  // Questions answered by the same request usually end with the same caveat ("If you leave this, we
  // assume the typical figure for Beirut…"). Lifted per request, it is said once above its group.
  const groups = groupQuestions(questions);

  function submit(event: FormEvent) {
    event.preventDefault();
    if (anyProblem) {
      setShowProblems(true);
      return;
    }
    const answers: Answer[] = questions
      .map((question) => ({ question, value: (values[question.key] ?? '').trim() }))
      .filter((answer) => answer.value !== '');
    void make.run(() => sendAnswers(api, answers));
  }

  const set = (key: string, value: string) => setValues((all) => ({ ...all, [key]: value }));

  return (
    <form className="form" onSubmit={submit} noValidate>
      {groups.map((group) => {
        const why = liftSharedEnding(group.map((q) => q.why));
        return (
          <div className="question-group" key={group[0]!.key}>
            {why.shared ? (
              <div className="banner banner--info" role="note">
                <p>{why.shared}</p>
              </div>
            ) : null}
            {group.map((q, i) => (
              <QuestionField
                key={q.key}
                question={q}
                why={why.particular[i] ?? ''}
                value={values[q.key] ?? ''}
                problem={showProblems ? problems[q.key] ?? null : null}
                onChange={(value) => set(q.key, value)}
              />
            ))}
          </div>
        );
      })}
      {showProblems && anyProblem ? (
        <p className="field__error" role="alert">
          Some answers need another look before you can continue.
        </p>
      ) : null}
      {make.error ? (
        <p className="field__error" role="alert">
          {make.error}
        </p>
      ) : null}
      <div className="actions">
        <Link to="/setup/next" className="button button--secondary">
          Back
        </Link>
        <button type="submit" className="button" disabled={make.busy}>
          {make.busy ? 'Making your plan…' : 'Make my plan'}
        </button>
      </div>
    </form>
  );
}

function QuestionField({
  question,
  why,
  value,
  problem,
  onChange,
}: {
  question: OpenQuestion;
  why: string;
  value: string;
  problem: string | null;
  onChange(value: string): void;
}) {
  const id = `q-${question.key.replace(/[^a-z0-9-]/gi, '-')}`;
  const error = problem ? (
    <span className="field__error" role="alert">
      {problem}
    </span>
  ) : null;

  if (isChoice(question)) {
    return (
      <fieldset className="question">
        <legend className="field__label">{question.question}</legend>
        {why ? <p className="question__why">{why}</p> : null}
        <div className="options">
          {question.choices.map((choice) => (
            <label key={choice.key} className="option">
              <input
                type="radio"
                name={id}
                value={choice.key}
                checked={value === choice.key}
                onChange={() => onChange(choice.key)}
              />
              <span>{choice.label}</span>
            </label>
          ))}
        </div>
        {error}
      </fieldset>
    );
  }

  const described = [question.covers.length ? `${id}-covers` : null, question.basis ? `${id}-basis` : null]
    .filter(Boolean)
    .join(' ');
  return (
    <fieldset className="question">
      <label className="field" htmlFor={id}>
        <span className="field__label">{question.question}</span>
      </label>
      {why ? <p className="question__why">{why}</p> : null}
      <div className="money-input">
        <span aria-hidden="true">$</span>
        <input
          id={id}
          type="text"
          inputMode="decimal"
          value={value}
          placeholder={question.suggested ?? ''}
          aria-invalid={problem !== null}
          aria-describedby={described || undefined}
          onChange={(e) => onChange(e.target.value)}
        />
      </div>
      {question.suggested !== null ? (
        <p className="field__help">Leave it blank to use {formatMoney(question.suggested)}.</p>
      ) : null}
      {error}
      {question.covers.length ? (
        <div id={`${id}-covers`} className="question__covers">
          <span>This covers:</span>
          <ul>
            {question.covers.map((line) => (
              <li key={line}>{line}</li>
            ))}
          </ul>
        </div>
      ) : null}
      {question.basis ? (
        <p id={`${id}-basis`} className="question__basis">
          {question.basis}
        </p>
      ) : null}
    </fieldset>
  );
}

/** Consecutive questions answered by the same request, kept in the server's order. */
function groupQuestions(questions: OpenQuestion[]): OpenQuestion[][] {
  const groups: OpenQuestion[][] = [];
  let lastPath: string | null = null;
  for (const question of questions) {
    const path = parseTarget(question.answerWith)?.path ?? question.key;
    if (path !== lastPath || groups.length === 0) groups.push([]);
    groups[groups.length - 1]!.push(question);
    lastPath = path;
  }
  return groups;
}

function editOf(profile: Profile): ProfileEdit {
  return {
    country: profile.country.code,
    city: profile.city?.id ?? null,
    cityNotListed: profile.cityNotListed,
    lifestyle: profile.lifestyle?.key ?? null,
    incomeArrivesTaxed: profile.incomeArrivesTaxed,
    leastForEnjoyingLife: profile.leastForEnjoyingLife,
  };
}

/**
 * Sends each route its answers, merged with what it already holds.
 *
 * All three routes take a whole body, and none of them merges on the server: the profile is replaced
 * wholesale, the money route refuses one figure without the other, and the spending map is replaced
 * rather than added to. So each is read first and sent back complete. A route this client does not
 * know how to complete is left alone - its questions stay open, and the server asks them again.
 */
export async function sendAnswers(api: ApiClient, answers: Answer[]): Promise<void> {
  const grouped = groupByTarget(answers);

  const profileAnswers = grouped.get('/api/v1/profile');
  if (profileAnswers) {
    const existing = await plan.profile(api);
    await plan.saveProfile(api, { ...editOf(existing), ...profileAnswers });
  }

  const moneyAnswers = grouped.get('/api/v1/money');
  if (moneyAnswers) {
    const existing = await orNothing(plan.money(api));
    const alreadySaving = moneyAnswers.alreadySaving ?? existing?.alreadySaving ?? null;
    await plan.saveMoney(api, {
      monthlyIncome: moneyAnswers.monthlyIncome ?? existing?.monthlyIncome ?? '',
      balance: moneyAnswers.balance ?? existing?.balance ?? '',
      // Left out, the route clears it - so a saved figure travels again unless this answers it.
      ...(alreadySaving === null ? {} : { alreadySaving }),
    });
  }

  const spendingAnswers = grouped.get('/api/v1/spending');
  if (spendingAnswers) {
    const existing = (await orNothing(plan.spending(api))) ?? {};
    await plan.saveSpending(api, { ...existing, ...spendingAnswers });
  }
}
