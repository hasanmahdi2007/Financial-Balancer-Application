import { useState, type FormEvent } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { useApi } from '../api/ApiProvider';
import { plan, type Choice, type GoalEdit, type SavedGoal } from '../api/plan';
import { useAction } from '../api/useAction';
import { useRemote } from '../api/useRemote';
import { AMOUNT_PROBLEM, isAmount } from '../ui/amount';
import { Failure, Loading } from '../ui/Feedback';
import { MoneyField } from '../ui/MoneyField';

/**
 * Adding a goal, or changing one - giving it more time is the usual reason, and it is one of the two
 * ways out the plan offers when the numbers will not close.
 *
 * Saving recomputes the whole plan on the server, so on success this goes straight to the plan, whose
 * "what changed" panel says what the new goal cost the others.
 */
export function GoalFormPage() {
  const { goalId } = useParams();
  const api = useApi();
  const choices = useRemote('choices', (signal) => plan.choices(api, signal));
  const goals = useRemote(goalId ? `goals-${goalId}` : null, (signal) => plan.goals(api, signal));

  const editing = goalId !== undefined;
  const existing = editing && goals.state === 'ready' ? goals.data.find((g) => g.id === goalId) : undefined;

  return (
    <section className="card">
      <h1>{editing ? 'Change a goal' : 'Add a goal'}</h1>
      <p className="lede">
        Your whole plan is worked out again when you save, and we will show you what this goal changes for the others.
      </p>
      {choices.state === 'loading' || (editing && goals.state === 'loading') ? <Loading what="your goal" /> : null}
      {choices.state === 'failed' ? <Failure message={choices.message} retry={choices.retry} /> : null}
      {editing && goals.state === 'failed' ? <Failure message={goals.message} retry={goals.retry} /> : null}
      {editing && goals.state === 'ready' && !existing ? (
        <Failure message="We could not find that goal. It may have been removed." />
      ) : null}
      {choices.state === 'ready' && (!editing || existing) ? (
        <GoalForm key={existing?.id ?? 'new'} priorities={choices.data.priorities} existing={existing} />
      ) : null}
    </section>
  );
}

function GoalForm({ priorities, existing }: { priorities: Choice[]; existing?: SavedGoal }) {
  const api = useApi();
  const navigate = useNavigate();
  const [name, setName] = useState(existing?.name ?? '');
  const [target, setTarget] = useState(existing?.target ?? '');
  const [deadline, setDeadline] = useState(existing?.deadline ?? '');
  const [priority, setPriority] = useState(existing?.priority.key ?? '');
  const [showProblems, setShowProblems] = useState(false);
  const [waitingFor, setWaitingFor] = useState<string | null>(null);

  const problems = {
    name: name.trim() === '' ? 'Give the goal a name, so the plan can tell you about it.' : null,
    target: !isAmount(target) || /^0+(\.0+)?$/.test(target.trim()) ? AMOUNT_PROBLEM : null,
    deadline: /^\d{4}-\d{2}-\d{2}$/.test(deadline) ? null : 'Pick the date you want this by.',
    priority: priorities.some((p) => p.key === priority) ? null : 'Say how much this goal matters to you.',
  };
  const anyProblem = Object.values(problems).some(Boolean);

  const save = useAction(async (edit: GoalEdit) => {
    const saved = existing ? await plan.changeGoal(api, existing.id, edit) : await plan.addGoal(api, edit);
    if (saved.plan) {
      navigate('/plan');
    } else {
      // The goal is kept; there is just no plan to show it in yet. Say what is missing, in the
      // server's words, rather than leaving the user on a plan page with nothing on it.
      setWaitingFor(saved.waitingFor);
    }
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    if (anyProblem) {
      setShowProblems(true);
      return;
    }
    void save.run({ name: name.trim(), target: target.trim(), deadline, priority });
  }

  if (waitingFor) {
    return (
      <div className="banner banner--info" role="note">
        <strong>Your goal is saved.</strong>
        <p>{waitingFor}</p>
        <Link to="/setup/questions" className="button">
          Answer the questions
        </Link>
      </div>
    );
  }

  const error = (text: string | null) =>
    showProblems && text ? (
      <span className="field__error" role="alert">
        {text}
      </span>
    ) : null;

  return (
    <form className="form" onSubmit={submit} noValidate>
      <div className="field">
        <label className="field__label" htmlFor="goal-name">
          What are you saving for?
        </label>
        <input id="goal-name" type="text" value={name} onChange={(e) => setName(e.target.value)} />
        {error(problems.name)}
      </div>
      <MoneyField
        label="How much will it cost?"
        value={target}
        onChange={setTarget}
        problem={showProblems ? problems.target : null}
      />
      <div className="field">
        <label className="field__label" htmlFor="goal-deadline">
          When do you want it by?
        </label>
        <input id="goal-deadline" type="date" value={deadline} onChange={(e) => setDeadline(e.target.value)} />
        {error(problems.deadline)}
      </div>
      <fieldset className="question">
        <legend className="field__label">How much does it matter?</legend>
        <p className="question__why">
          Goals that matter more are paid first. When there is not enough for everything, the ones below wait.
        </p>
        <div className="options">
          {priorities.map((p) => (
            <label key={p.key} className="option">
              <input
                type="radio"
                name="priority"
                value={p.key}
                checked={priority === p.key}
                onChange={() => setPriority(p.key)}
              />
              <span>
                <strong>{p.label}</strong> - {p.covers}
              </span>
            </label>
          ))}
        </div>
        {error(problems.priority)}
      </fieldset>
      {save.error ? (
        <p className="field__error" role="alert">
          {save.error}
        </p>
      ) : null}
      <div className="actions">
        <Link to="/plan" className="button button--secondary">
          Cancel
        </Link>
        <button type="submit" className="button" disabled={save.busy}>
          {save.busy ? 'Working out your plan…' : existing ? 'Save and replan' : 'Add and replan'}
        </button>
      </div>
    </form>
  );
}
