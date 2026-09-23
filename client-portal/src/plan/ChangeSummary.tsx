import type { PlanChange } from '../api/plan';
import { formatMoney } from '../ui/amount';

/**
 * What one plan changed compared with the one before it.
 *
 * This is where re-ranking becomes visible. Adding a goal that matters more does not only fund the
 * new goal - it takes money from the ones below it, and a goal that quietly drops from "Behind" to
 * "Not funded yet" on the next screen looks like a bug unless the screen says why. So every goal
 * whose funding moved is listed with its before and after, beside the reason the plan was made.
 */
export function ChangeSummary({ changes }: { changes: PlanChange }) {
  const moved = changes.goals.filter(
    (g) =>
      g.before.status !== g.after.status ||
      g.before.monthlyFunded !== g.after.monthlyFunded ||
      g.before.fromBalance !== g.after.fromBalance,
  );
  const surplusMoved = changes.surplus.before !== changes.surplus.after;

  return (
    <div className="changes">
      {changes.goalsAdded.length > 0 ? <p>Added: {changes.goalsAdded.join(', ')}.</p> : null}
      {changes.goalsRemoved.length > 0 ? <p>Removed: {changes.goalsRemoved.join(', ')}.</p> : null}
      {surplusMoved ? (
        <p>
          Each month for your goals: {formatMoney(changes.surplus.before)} → {formatMoney(changes.surplus.after)}
        </p>
      ) : null}
      {moved.length > 0 ? (
        <>
          <p>Because of this, other goals moved:</p>
          <ul>
            {moved.map((g) => (
              <li key={g.id}>
                <strong>{g.name}</strong>: {g.before.status}, {formatMoney(g.before.monthlyFunded)} a month and{' '}
                {formatMoney(g.before.fromBalance)} from savings → {g.after.status},{' '}
                {formatMoney(g.after.monthlyFunded)} a month and {formatMoney(g.after.fromBalance)} from savings
              </li>
            ))}
          </ul>
        </>
      ) : null}
      {!surplusMoved && moved.length === 0 && changes.goalsAdded.length + changes.goalsRemoved.length === 0 ? (
        <p>Nothing else moved.</p>
      ) : null}
    </div>
  );
}
