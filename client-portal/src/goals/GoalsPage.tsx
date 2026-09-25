import { useState } from 'react';
import { Link } from 'react-router';
import { useApi } from '../api/ApiProvider';
import { plan as api, type PlanGoal } from '../api/plan';
import { useAction } from '../api/useAction';
import { useRemote } from '../api/useRemote';
import { formatCalendarDate } from '../provenance/Provenance';
import { orNothing } from '../setup/saveLocation';
import { Progress } from '../plan/PlanView';
import { formatMoney } from '../ui/amount';
import { Failure, Loading } from '../ui/Feedback';

/**
 * Every goal in the plan in use, and how far each has got.
 *
 * Progress is what the money the user already has covers - the only way a goal holds money here,
 * which is what stops the same dollars counting twice - beside what it needs and gets each month.
 * Every figure is the plan's, exactly as it was last made; nothing here recomputes one.
 */
export function GoalsPage() {
  const client = useApi();
  const [version, setVersion] = useState(0);
  const latest = useRemote(`plan-${version}`, (signal) => orNothing(api.latest(client, signal)));
  // Goals exist before a plan does - added while setup is still unfinished - so they are read too.
  const drafts = useRemote(`goals-${version}`, (signal) => orNothing(api.goals(client, signal)));
  const remove = useAction(async (goalId: string) => {
    await api.removeGoal(client, goalId);
    setVersion((v) => v + 1);
  });

  if (latest.state === 'loading' || drafts.state === 'loading') return <Loading what="your goals" />;
  if (latest.state === 'failed') return <Failure message={latest.message} retry={latest.retry} />;
  if (drafts.state === 'failed') return <Failure message={drafts.message} retry={drafts.retry} />;

  const goals = latest.data?.goals ?? [];
  const unplanned = latest.data ? [] : (drafts.data ?? []);

  return (
    <section className="dashboard">
      <div className="page-head">
        <h1>Your goals</h1>
        <div className="actions">
          <Link to="/goals/new" className="button">
            Add a goal
          </Link>
        </div>
      </div>
      {remove.error ? <Failure message={remove.error} /> : null}

      {goals.length === 0 && unplanned.length === 0 ? (
        <section className="panel">
          <p>You have not added a goal yet. Add one and we will show how far it has got and what it needs.</p>
        </section>
      ) : null}

      {unplanned.length > 0 ? (
        <section className="panel" aria-labelledby="unplanned-heading">
          <h2 id="unplanned-heading">Waiting for your plan</h2>
          <p>Finish telling us about your money and we will show how far each of these has got.</p>
          <ul>
            {unplanned.map((goal) => (
              <li key={goal.id}>
                {goal.name}: {formatMoney(goal.target)} by{' '}
                <time dateTime={goal.deadline}>{formatCalendarDate(goal.deadline)}</time>
              </li>
            ))}
          </ul>
          <Link to="/setup/questions" className="button button--secondary">
            Finish setting up
          </Link>
        </section>
      ) : null}

      {goals.length > 0 ? (
        <ol className="goal-list">
          {goals.map((goal) => (
            <GoalCard key={goal.id} goal={goal} busy={remove.busy} onRemove={() => void remove.run(goal.id)} />
          ))}
        </ol>
      ) : null}
    </section>
  );
}

function GoalCard({ goal, busy, onRemove }: { goal: PlanGoal; busy: boolean; onRemove(): void }) {
  return (
    <li className="panel goal-card">
      <div className="goal-card__head">
        <h2>{goal.name}</h2>
        {goal.percentCovered !== null && goal.percentCovered !== undefined ? (
          <p className="goal-card__percent" aria-label={`${goal.percentCovered}% of the way there`}>
            {goal.percentCovered}%
          </p>
        ) : null}
      </div>
      <p className="goal__meta">
        {goal.priority.label} · {formatMoney(goal.target)} by{' '}
        <time dateTime={goal.deadline}>{formatCalendarDate(goal.deadline)}</time>
        {goal.finishFirst ? ' · Takes your savings first' : null}
      </p>
      <Progress
        label="Covered by what you already have"
        part={goal.fromBalance}
        whole={goal.target}
        percent={goal.percentCovered}
      />
      <p className="goal__status">
        <strong>{goal.status.label}.</strong> {goal.status.meaning}
      </p>
      <dl className="figures">
        <dt>Still needed</dt>
        <dd>{formatMoney(goal.stillNeeded)}</dd>
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
      <div className="actions">
        <Link to={`/goals/${encodeURIComponent(goal.id)}`} className="button button--secondary">
          Change
        </Link>
        <button type="button" className="button button--quiet" disabled={busy} onClick={onRemove}>
          Remove
        </button>
      </div>
    </li>
  );
}
