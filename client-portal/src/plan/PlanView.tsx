import type { ReactNode } from 'react';
import { Link } from 'react-router';
import type { Basis, Cuts, Plan, PlanGoal, Surplus, WayOut } from '../api/plan';
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
 */
export function PlanView({ plan, goalActions }: { plan: Plan; goalActions?: (goal: PlanGoal) => ReactNode }) {
  return (
    <div className="plan">
      <p className="plan__made">
        Made <time dateTime={plan.takenAt}>{formatCalendarDate(plan.takenAt.slice(0, 10))}</time> · {plan.reason}
      </p>
      <SurplusCard surplus={plan.surplus} />
      <GoalsSection goals={plan.goals} actions={goalActions} />
      <CutsSection cuts={plan.cuts} />
      <LeftOver plan={plan} />
      <SpendingSection plan={plan} />
      <MoneySection plan={plan} editable={goalActions !== undefined} />
    </div>
  );
}

function SurplusCard({ surplus }: { surplus: Surplus }) {
  return (
    <section className="panel" aria-labelledby="surplus-heading">
      <h2 id="surplus-heading">Each month for your goals</h2>
      <p className="figure">{formatMoney(surplus.amount)}</p>
      <p>{surplus.explanation}</p>
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
            <p className="goal__status">
              <strong>{goal.status.label}.</strong> {goal.status.meaning}
            </p>
            <dl className="figures">
              <dt>From what you already have</dt>
              <dd>{formatMoney(goal.fromBalance)}</dd>
              <dt>Needs each month</dt>
              <dd>{formatMoney(goal.monthlyNeeded)}</dd>
              <dt>Gets each month</dt>
              <dd>{formatMoney(goal.monthlyFunded)}</dd>
              {goal.shortBy !== '0.00' ? (
                <>
                  <dt>Short each month by</dt>
                  <dd>{formatMoney(goal.shortBy)}</dd>
                </>
              ) : null}
            </dl>
            {actions ? <div className="actions">{actions(goal)}</div> : null}
          </li>
        ))}
      </ol>
    </section>
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

function SpendingSection({ plan }: { plan: Plan }) {
  const lines = plan.surplus.lines;
  const hints = new Map(plan.hints.map((h) => [h.id, h.hint]));
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
                  {line.assumed ? <span className="aside"> (our estimate - you have not told us yours)</span> : null}
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
    <section className="panel" aria-labelledby="money-heading">
      <h2 id="money-heading">Your money</h2>
      <dl className="figures">
        <dt>Arrives each month</dt>
        <dd>{formatMoney(money.monthlyIncome)}</dd>
        <dt>Savings this plan may use</dt>
        <dd>{formatMoney(money.balanceInScope)}</dd>
        <dt>Put toward goals</dt>
        <dd>{formatMoney(money.putTowardGoals)}</dd>
        <dt>Not yet given to a goal</dt>
        <dd>{formatMoney(money.leftUnassigned)}</dd>
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
      <p className="aside">{surplus.alreadySavingExplanation}</p>
      <p>{money.runway.label}</p>
      {editable ? (
        <Link to="/money" className="button button--secondary">
          Change these
        </Link>
      ) : null}
    </section>
  );
}
