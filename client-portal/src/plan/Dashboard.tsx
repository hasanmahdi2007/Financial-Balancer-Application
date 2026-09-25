import { useState } from 'react';
import { Link } from 'react-router';
import { useApi } from '../api/ApiProvider';
import { plan as api, type PlanGoal } from '../api/plan';
import { useAction } from '../api/useAction';
import { useRemote } from '../api/useRemote';
import { orNothing } from '../setup/saveLocation';
import { Failure, Loading } from '../ui/Feedback';
import { ChangeSummary } from './ChangeSummary';
import { PlanView } from './PlanView';

/**
 * The latest plan, what changed to make it, and the doors to everything that changes it next.
 *
 * Nothing here recomputes on its own. A plan is a snapshot the server keeps, and the one on screen
 * is always the one it last made - so what the user sees is exactly what the history will show.
 */
export function Dashboard() {
  const client = useApi();
  // Bumped after anything that makes a new plan, so both the plan and its history are read again
  // together and can never describe two different snapshots.
  const [version, setVersion] = useState(0);
  const latest = useRemote(`plan-${version}`, (signal) => orNothing(api.latest(client, signal)));
  const history = useRemote(`history-${version}`, (signal) => orNothing(api.history(client, signal)));
  const refresh = () => setVersion((v) => v + 1);

  const act = useAction(async (change: () => Promise<unknown>) => {
    await change();
    refresh();
  });

  if (latest.state === 'loading') return <Loading what="your plan" />;
  if (latest.state === 'failed') return <Failure message={latest.message} retry={latest.retry} />;

  const current = latest.data;
  if (!current) {
    return (
      <section className="card">
        <h1>You do not have a plan yet</h1>
        <p>Tell us where you live and a little about your money, and we will make one.</p>
        <Link to="/setup/location" className="button">
          Get started
        </Link>
      </section>
    );
  }

  // The newest history entry describes this plan only if it *is* this plan; otherwise it is stale.
  const newest = history.state === 'ready' ? history.data?.[0] : undefined;
  const changes = newest && newest.id === current.id ? newest.changes : null;
  const someoneFirst = current.goals.some((g) => g.finishFirst);

  const goalActions = (goal: PlanGoal) => (
    <>
      <Link to={`/goals/${encodeURIComponent(goal.id)}`} className="button button--secondary">
        Change
      </Link>
      <button
        type="button"
        className="button button--secondary"
        disabled={act.busy}
        onClick={() => void act.run(() => api.finishFirst(client, goal.finishFirst ? null : goal.id))}
      >
        {goal.finishFirst ? 'Back to priority order' : 'Give this my savings first'}
      </button>
      <button
        type="button"
        className="button button--quiet"
        disabled={act.busy}
        onClick={() => void act.run(() => api.removeGoal(client, goal.id))}
      >
        Remove
      </button>
    </>
  );

  return (
    <section className="dashboard">
      <div className="page-head">
        <h1>Your plan</h1>
        {/* The two things people come here to do; everything else is in the menu beside the page. */}
        <div className="actions">
          <Link to="/goals/new" className="button">
            Add a goal
          </Link>
          <Link to="/afford" className="button button--orange">
            Can I afford this?
          </Link>
          {/* Hasan asked for this one on the page itself, not only in the menu. */}
          <Link to="/plans/new" className="button button--secondary">
            New plan
          </Link>
        </div>
      </div>
      {act.error ? <Failure message={act.error} /> : null}
      {changes ? (
        <aside className="banner banner--info" aria-label="What changed">
          <strong>What changed: {current.reason}</strong>
          <ChangeSummary changes={changes} />
        </aside>
      ) : null}
      {someoneFirst ? (
        <p className="aside">One goal is taking your savings ahead of priority order, because you asked it to.</p>
      ) : null}
      <PlanView plan={current} goalActions={goalActions} />
    </section>
  );
}
