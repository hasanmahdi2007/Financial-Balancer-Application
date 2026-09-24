import type { ReactNode } from 'react';
import { Link } from 'react-router';
import type { Basis, Cuts, Plan, PlanGoal, Surplus, Today, WayOut } from '../api/plan';
import { formatCalendarDate, GatheredOn, ProvenanceBadge, StalenessBanner } from '../provenance/Provenance';
import { formatMoney } from '../ui/amount';

/**
 * One plan, laid out. Used for the latest plan and for any past one, which is why it takes the
 * goal actions as a prop: a past plan is a record, and nothing on it can be changed.
 *
 * Every sentence below that explains a figure came from the server. What this component decides is
 * only which figures must never be shown apart - the monthly amount without what it already assumes,
 * or suggested cuts without the changes they come on top of - because shown alone, each one says
 * something the plan does not.
 *
 * A plan opens on where the user stands from their own figures, then the changes that improve it, and
 * only then the plan's own monthly figure - the result of those changes, never the first number they
 * read. A plan made before that panel existed is shown exactly as it was then: it is a record of what
 * the user was told, so nothing here works the missing figure out from its lines.
 */
export function PlanView({ plan, goalActions }: { plan: Plan; goalActions?: (goal: PlanGoal) => ReactNode }) {
  return (
    <div className="plan">
      {plan.today ? (
        <>
          {/* The design's hero belongs to the first thing a person should read, which is now where
              they stand - the plan's own figure arrives below, as the result of the changes. */}
          <TodayCard plan={plan} today={plan.today} />
          <ImproveSection plan={plan} today={plan.today} />
        </>
      ) : (
        <SurplusCard plan={plan} />
      )}
      <MoneySection plan={plan} editable={goalActions !== undefined} />
      <div className="plan__grid">
        <GoalsSection goals={plan.goals} actions={goalActions} />
        <div className="plan__side">
          {/* On a current plan the cuts are among the changes above, so they are said once. */}
          {plan.today ? null : <CutsSection cuts={plan.cuts} />}
          <LeftOver plan={plan} />
        </div>
      </div>
      {/* Hints move up into the changes on a current plan, for the same reason. */}
      <SpendingSection plan={plan} showHints={!plan.today} />
    </div>
  );
}

/**
 * What is really left each month from the figures exactly as entered. Nothing in it is assumed, which
 * is the whole claim of the card - so it says how many of its figures are our estimate, when any are.
 */
function TodayCard({ plan, today }: { plan: Plan; today: Today }) {
  return (
    <section className="hero" aria-labelledby="today-heading">
      <div className="hero__main">
        <p className="plan__made">
          Made <time dateTime={plan.takenAt}>{formatCalendarDate(plan.takenAt.slice(0, 10))}</time> · {plan.reason}
        </p>
        <h2 id="today-heading">Where you stand today</h2>
        <p className="figure">{formatMoney(today.leftAsEntered)}</p>
        <p className="hero__explanation">{today.leftAsEnteredExplanation}</p>
      </div>
      <div className="assumes" role="group" aria-label="How this adds up">
        <ul>
          <li>Arrives each month: {formatMoney(today.income)}</li>
          {today.taxSetAside !== null ? <li>Tax set aside: {formatMoney(today.taxSetAside)}</li> : null}
          <li>What you spend: {formatMoney(today.spent)}</li>
          <li>
            <strong>Left each month: {formatMoney(today.leftAsEntered)}</strong>
          </li>
        </ul>
        {/* Someone spending more than they earn needs to know how long their savings cover it. */}
        {today.runway ? <p>{today.runway}</p> : null}
        {today.alreadySavingNote ? <p>{today.alreadySavingNote}</p> : null}
        {today.estimatedNote ? <p className="aside">{today.estimatedNote}</p> : null}
      </div>
    </section>
  );
}

/**
 * Each change the plan counts on, as what it adds per month, closing with the plan's own figure as
 * their result. That order is also what keeps the figure honest: it can only be read after the
 * changes it depends on.
 */
function ImproveSection({ plan, today }: { plan: Plan; today: Today }) {
  const { surplus, cuts, hints } = plan;
  const anything = surplus.reductions.length + cuts.suggested.length + hints.length > 0;
  return (
    <section className="panel" aria-labelledby="improve-heading">
      <h2 id="improve-heading">How to improve it</h2>
      {anything ? null : <p>There is nothing we would change.</p>}
      {surplus.reductions.length + cuts.suggested.length > 0 ? (
        <ul className="tips" aria-label="Changes and what each adds">
          {surplus.reductions.map((r) => (
            <li key={`reduce-${r.label}`}>
              Bring {r.label} from {formatMoney(r.from)} to {formatMoney(r.to)}:{' '}
              <strong>+{formatMoney(r.by)} a month</strong>
            </li>
          ))}
          {cuts.suggested.map((cut) => (
            <li key={`cut-${cut.label}`}>
              Spend less on {cut.label}: <strong>+{formatMoney(cut.by)} a month</strong>
              <span className="aside"> ({cut.howWilling.label})</span>
            </li>
          ))}
        </ul>
      ) : null}
      {/* Words only. A hint is never a figure and never enters a total: it is advice about where a
          line could move, not money the plan has found. */}
      {hints.map((h) => (
        <p key={h.id} className="hint">
          <strong>{h.label}:</strong> {h.hint}
        </p>
      ))}
      {cuts.stillShort !== '0.00' ? (
        <div className="banner banner--warning" role="note">
          <strong>Even with all of that, you are still {formatMoney(cuts.stillShort)} a month short.</strong>
          {cuts.options.length > 0 ? <WaysOut options={cuts.options} /> : null}
        </div>
      ) : null}
      <div role="group" aria-label="The result">
        <h3>Each month for your goals</h3>
        <p>{today.planResult}</p>
        <p className="figure">{formatMoney(surplus.amount)}</p>
        <p>{surplus.explanation}</p>
        <dl className="figures">
          <dt>Everything you would change</dt>
          <dd>{formatMoney(cuts.totalChange)}</dd>
        </dl>
        <p className="aside">{surplus.assumedReductionExplanation}</p>
      </div>
    </section>
  );
}

function SurplusCard({ plan }: { plan: Plan }) {
  const surplus: Surplus = plan.surplus;
  return (
    <section className="hero" aria-labelledby="surplus-heading">
      <div className="hero__main">
        <p className="plan__made">
          Made <time dateTime={plan.takenAt}>{formatCalendarDate(plan.takenAt.slice(0, 10))}</time> · {plan.reason}
        </p>
        <h2 id="surplus-heading">Each month for your goals</h2>
        <p className="figure">{formatMoney(surplus.amount)}</p>
        <p className="hero__explanation">{surplus.explanation}</p>
      </div>
      {/* Never the amount alone: it is only reachable if these changes happen, and a reader who
          missed that would treat it as money in hand. */}
      {surplus.reductions.length > 0 ? (
        <div className="assumes" role="group" aria-label="What this already assumes">
          <p>
            <strong>This already assumes you spend {formatMoney(surplus.assumedReduction)} less a month:</strong>
          </p>
          <ul>
            {surplus.reductions.map((r) => (
              <li key={r.label}>
                {r.label}: {formatMoney(r.from)} → {formatMoney(r.to)}
              </li>
            ))}
          </ul>
          <p className="aside">{surplus.assumedReductionExplanation}</p>
        </div>
      ) : null}
    </section>
  );
}

function GoalsSection({ goals, actions }: { goals: PlanGoal[]; actions?: (goal: PlanGoal) => ReactNode }) {
  return (
    <section className="panel" id="goals" aria-labelledby="goals-heading">
      <h2 id="goals-heading">Your goals</h2>
      {goals.length === 0 ? <p>You have not added a goal yet.</p> : null}
      {/* In the order the server funded them, which is the order that explains why one goal is
          short: everything above it was paid first. */}
      <ol className="goals">
        {goals.map((goal) => (
          <li key={goal.id} className="goal">
            <h3>{goal.name}</h3>
            <p className="goal__meta">
              {goal.priority.label} · {formatMoney(goal.target)} by{' '}
              <time dateTime={goal.deadline}>{formatCalendarDate(goal.deadline)}</time>
              {goal.finishFirst ? ' · Takes your savings first' : null}
            </p>
            {/* Coloured by the figures, not by the wording: a goal short of money each month is
                amber whatever the server calls that, so a new status needs no change here. */}
            <p className={goal.shortBy === '0.00' ? 'goal__status goal__status--ok' : 'goal__status goal__status--short'}>
              <strong>{goal.status.label}.</strong> {goal.status.meaning}
            </p>
            <Progress label="Covered by what you already have" part={goal.fromBalance} whole={goal.target} />
            {goal.monthlyNeeded !== '0.00' ? (
              <Progress label="Monthly amount it gets" part={goal.monthlyFunded} whole={goal.monthlyNeeded} />
            ) : null}
            {/* The bars above already carry what it has and what it gets; only the gap is left to say. */}
            {goal.shortBy !== '0.00' ? (
              <dl className="figures">
                <dt>Short each month by</dt>
                <dd>{formatMoney(goal.shortBy)}</dd>
              </dl>
            ) : null}
            {actions ? <div className="actions">{actions(goal)}</div> : null}
          </li>
        ))}
      </ol>
    </section>
  );
}

/**
 * A bar showing how much of one figure another covers. The ratio is for drawing only - it sets a
 * width - and never becomes a figure anyone reads, so a float is harmless here in a way it would not
 * be for the amounts themselves, which are shown exactly as the server sent them.
 */
function Progress({ label, part, whole }: { label: string; part: string; whole: string }) {
  const ratio = Number(whole) > 0 ? Math.min(1, Math.max(0, Number(part) / Number(whole))) : 0;
  const percent = Math.round(ratio * 100);
  return (
    <div className="progress">
      <div className="progress__label">
        <span>{label}</span>
        <span>
          {formatMoney(part)} of {formatMoney(whole)}
        </span>
      </div>
      <div className="progress__track" role="img" aria-label={`${label}: ${percent}%`}>
        <div className={ratio >= 1 ? 'progress__bar progress__bar--full' : 'progress__bar'} style={{ width: `${percent}%` }} />
      </div>
    </div>
  );
}

function CutsSection({ cuts }: { cuts: Cuts }) {
  if (cuts.suggested.length === 0 && cuts.stillShort === '0.00') return null;
  return (
    <section className="panel" aria-labelledby="cuts-heading">
      <h2 id="cuts-heading">What would have to change</h2>
      {cuts.suggested.length > 0 ? (
        <>
          <p>On top of what the monthly figure already assumes, we suggest:</p>
          <ul>
            {cuts.suggested.map((cut) => (
              <li key={cut.label}>
                {cut.label}: {formatMoney(cut.by)} less a month
                <span className="aside"> ({cut.howWilling.label})</span>
              </li>
            ))}
          </ul>
        </>
      ) : null}
      {/* The suggested cuts are only the part on top. Shown without these two figures they would read
          as the whole change, and someone who made only them would still come up short. */}
      <dl className="figures">
        <dt>Already assumed</dt>
        <dd>{formatMoney(cuts.alreadyAssumed)}</dd>
        <dt>Suggested on top</dt>
        <dd>{formatMoney(cuts.suggestedTotal)}</dd>
        <dt>Everything you would change</dt>
        <dd>{formatMoney(cuts.totalChange)}</dd>
      </dl>
      <p className="aside">{cuts.totalChangeExplanation}</p>
      {cuts.stillShort !== '0.00' ? (
        <div className="banner banner--warning" role="note">
          <strong>Even with all of that, you are still {formatMoney(cuts.stillShort)} a month short.</strong>
          {cuts.options.length > 0 ? <WaysOut options={cuts.options} /> : null}
        </div>
      ) : null}
    </section>
  );
}

/**
 * Where each way out is acted on. A route table, not a label map: the words come from the server and
 * this only says which screen performs the request it names. An option naming a request with no
 * screen here is still shown, just without a link, so a new one appears the day the server sends it.
 */
const ACTS_ON: [prefix: string, screen: string][] = [
  ['PUT /api/v1/money', '/money'],
  ['PUT /api/v1/goals/', '/plan#goals'],
];

export function WaysOut({ options }: { options: WayOut[] }) {
  return (
    <ul className="ways-out">
      {options.map((option) => {
        const screen = ACTS_ON.find(([prefix]) => option.answerWith.startsWith(prefix))?.[1];
        return (
          <li key={option.label}>
            {screen ? <Link to={screen}>{option.label}</Link> : <strong>{option.label}</strong>}
            <p>{option.meaning}</p>
          </li>
        );
      })}
    </ul>
  );
}

function LeftOver({ plan }: { plan: Plan }) {
  if (plan.leftOver.amount === '0.00') return null;
  return (
    <section className="panel" aria-labelledby="leftover-heading">
      <h2 id="leftover-heading">Left over</h2>
      <p className="figure">{formatMoney(plan.leftOver.amount)}</p>
      <p>{plan.leftOver.explanation}</p>
    </section>
  );
}

function basisKey(basis: Basis): string {
  return `${basis.label}|${basis.explanation}|${basis.gathered}`;
}

function SpendingSection({ plan, showHints }: { plan: Plan; showHints: boolean }) {
  const lines = plan.surplus.lines;
  const hints = new Map<string, string>(showHints ? plan.hints.map((h) => [h.id, h.hint]) : []);
  // Each source is explained once, below the table, rather than on all eleven rows it applies to.
  const sources = [...new Map(lines.flatMap((l) => (l.basis ? [[basisKey(l.basis), l.basis] as const] : []))).values()];
  // One warning per distinct tier, however many lines share it.
  const ageing = [
    ...new Map(
      lines.flatMap((l) => (l.basis?.ageing ? [[l.basis.ageing.label, l.basis.ageing] as const] : [])),
    ).values(),
  ];

  return (
    <section className="panel" aria-labelledby="spending-heading">
      <h2 id="spending-heading">What you spend</h2>
      {ageing.map((tier) => (
        <StalenessBanner key={tier.label} headline={tier.label} detail={tier.meaning} />
      ))}
      <table className="lines">
        <thead>
          <tr>
            <th scope="col">What</th>
            <th scope="col">You spend</th>
            <th scope="col">Typical here</th>
            <th scope="col">Source</th>
          </tr>
        </thead>
        <tbody>
          {lines.map((line) => {
            const hint = hints.get(line.id);
            return (
              <tr key={line.id}>
                <th scope="row">
                  {line.label}
                  {/* A sentence, never a figure, and never part of any total: it is advice about
                      where this line could move, not money the plan has found. */}
                  {hint ? <p className="hint">{hint}</p> : null}
                </th>
                <td>
                  {formatMoney(line.spent)}
                  {line.measuredFrom ? (
                    <span className="aside"> ({line.measuredFrom})</span>
                  ) : line.assumed ? (
                    <span className="aside"> (our estimate - you have not told us yours)</span>
                  ) : null}
                </td>
                <td>{line.localFigure === null ? '-' : formatMoney(line.localFigure)}</td>
                <td>{line.basis ? line.basis.label : '-'}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
      {sources.length > 0 ? (
        <div className="sources">
          <h3>Where these figures come from</h3>
          {sources.map((basis) => (
            <div key={basisKey(basis)}>
              <ProvenanceBadge basis={basis.label} explanation={basis.explanation} />
              <GatheredOn date={basis.gathered} />
            </div>
          ))}
        </div>
      ) : null}
    </section>
  );
}

function MoneySection({ plan, editable }: { plan: Plan; editable: boolean }) {
  const { money, surplus } = plan;
  return (
    <section className="money" aria-labelledby="money-heading">
      <div className="money__head">
        <h2 id="money-heading">Your money</h2>
        {editable ? (
          <Link to="/money" className="button button--secondary">
            Change these
          </Link>
        ) : null}
      </div>
      <dl className="tiles">
        <div className="tile tile--blue">
          <dt>Arrives each month</dt>
          <dd>{formatMoney(money.monthlyIncome)}</dd>
        </div>
        <div className="tile tile--green">
          <dt>Savings this plan may use</dt>
          <dd>{formatMoney(money.balanceInScope)}</dd>
        </div>
        <div className="tile tile--orange">
          <dt>Put toward goals</dt>
          <dd>{formatMoney(money.putTowardGoals)}</dd>
        </div>
        <div className="tile tile--plain">
          <dt>Not yet given to a goal</dt>
          <dd>{formatMoney(money.leftUnassigned)}</dd>
        </div>
      </dl>
      <dl className="figures money__more">
        {surplus.leastForEnjoyingLife !== null ? (
          <>
            <dt>The least you want for enjoying life</dt>
            <dd>
              {formatMoney(surplus.leastForEnjoyingLife)}
              {surplus.leastForEnjoyingLifeBasis ? (
                <span className="aside"> ({surplus.leastForEnjoyingLifeBasis})</span>
              ) : null}
            </dd>
          </>
        ) : null}
        <dt>Already moving into savings</dt>
        <dd>{formatMoney(surplus.alreadySaving)}</dd>
      </dl>
      <p className="aside">
        {surplus.alreadySavingExplanation} {money.runway.label}.
      </p>
    </section>
  );
}
