import { Link, useParams } from 'react-router';
import { useApi } from '../api/ApiProvider';
import { plan } from '../api/plan';
import { useRemote } from '../api/useRemote';
import { formatCalendarDate } from '../provenance/Provenance';
import { Failure, Loading } from '../ui/Feedback';
import { ChangeSummary } from './ChangeSummary';
import { PlanView } from './PlanView';

/**
 * Every plan ever made, newest first, each with the reason it was made and what it changed.
 * Nothing here is ever edited: a recompute appends a snapshot, so this is a true record.
 */
export function HistoryPage() {
  const api = useApi();
  const history = useRemote('history', (signal) => plan.history(api, signal));
  return (
    <section className="card">
      <h1>Every plan so far</h1>
      <Link to="/plan">Back to your plan</Link>
      {history.state === 'loading' ? <Loading what="your plans" /> : null}
      {history.state === 'failed' ? <Failure message={history.message} retry={history.retry} /> : null}
      {history.state === 'ready' ? (
        history.data.length === 0 ? (
          <p>You have not made a plan yet.</p>
        ) : (
          <ol className="history">
            {history.data.map((entry) => (
              <li key={entry.id}>
                <h2>
                  <Link to={`/plan/history/${encodeURIComponent(entry.id)}`}>{entry.reason}</Link>
                </h2>
                <p className="aside">
                  <time dateTime={entry.takenAt}>{formatCalendarDate(entry.takenAt.slice(0, 10))}</time>
                </p>
                {entry.changes ? <ChangeSummary changes={entry.changes} /> : <p>Your first plan.</p>}
              </li>
            ))}
          </ol>
        )
      ) : null}
    </section>
  );
}

/** One past plan exactly as it was, with nothing on it that could change it. */
export function PastPlanPage() {
  const { planId = '' } = useParams();
  const api = useApi();
  const past = useRemote(`past-${planId}`, (signal) => plan.pastPlan(api, planId, signal));
  return (
    <section className="dashboard">
      <p>
        <Link to="/plan/history">Back to every plan</Link>
      </p>
      <h1>A past plan</h1>
      <p className="aside">This is how your plan looked when it was made. It is kept as a record and cannot change.</p>
      {past.state === 'loading' ? <Loading what="that plan" /> : null}
      {past.state === 'failed' ? <Failure message={past.message} retry={past.retry} /> : null}
      {past.state === 'ready' ? <PlanView plan={past.data} /> : null}
    </section>
  );
}
