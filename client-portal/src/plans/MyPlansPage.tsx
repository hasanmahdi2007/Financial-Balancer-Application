import { Link, useNavigate } from 'react-router';
import { useApi } from '../api/ApiProvider';
import { plans } from '../api/plans';
import { useAction } from '../api/useAction';
import { useRemote } from '../api/useRemote';
import { Failure, Loading } from '../ui/Feedback';
import { PlanChoiceList } from './PlanChoiceList';

/**
 * Every plan the user has, in every country, with a way to pick any of them up.
 *
 * Without this, the only way back to a plan in another country would be to claim to have moved there.
 */
export function MyPlansPage() {
  const api = useApi();
  const navigate = useNavigate();
  const all = useRemote('plans', (signal) => plans.list(api, undefined, signal));
  const use = useAction(async (planId: string) => {
    await plans.use(api, planId);
    navigate('/plan');
  });

  return (
    <section className="card">
      <h1>My plans</h1>
      <p className="lede">One plan for each place. Picking one up brings back its goals, spending and history.</p>
      <div className="actions">
        <Link to="/plans/new" className="button button--secondary">
          New plan
        </Link>
      </div>
      {all.state === 'loading' ? <Loading what="your plans" /> : null}
      {all.state === 'failed' ? <Failure message={all.message} retry={all.retry} /> : null}
      {all.state === 'ready' && all.data.plans.length === 0 ? (
        <p>You do not have a plan yet. Start one for where you live.</p>
      ) : null}
      {all.state === 'ready' && all.data.plans.length > 0 ? (
        <PlanChoiceList choices={all.data} busy={use.busy} onUse={(planId) => void use.run(planId)} />
      ) : null}
      {use.error ? <Failure message={use.error} /> : null}
    </section>
  );
}
