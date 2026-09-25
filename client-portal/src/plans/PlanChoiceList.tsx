import type { PlanChoices } from '../api/plans';
import { formatCalendarDate } from '../provenance/Provenance';
import { formatMoney } from '../ui/amount';

/**
 * The user's plans as a list to choose from: where each is for, when it was last used, what it last
 * showed, and a button to pick it up.
 *
 * No page chrome, because it is shared: "New plan" shows it for the country just chosen, "My plans"
 * for every country, and the chooser after a move for the country moved to. Every sentence on it is
 * the server's; this only lays them out.
 */
export function PlanChoiceList({
  choices,
  onUse,
  onStartNew,
  busy = false,
}: {
  choices: PlanChoices;
  onUse(planId: string): void;
  /** Leave out where starting a plan is not offered from this list. */
  onStartNew?(): void;
  busy?: boolean;
}) {
  return (
    <div className="plan-choices">
      <ul className="plan-choices__list">
        {choices.plans.map((plan) => (
          <li key={plan.id} className="plan-choice">
            <h3>{plan.place.label}</h3>
            <p className="plan-choice__meta">
              Last used <time dateTime={plan.lastUsedAt}>{formatCalendarDate(plan.lastUsedAt.slice(0, 10))}</time>
            </p>
            {plan.summary ? (
              <dl className="figures">
                <dt>{plan.summary.leftEachMonthLabel}</dt>
                <dd>{formatMoney(plan.summary.leftEachMonth)}</dd>
                <dt>Goals</dt>
                <dd>{plan.summary.goalsLabel}</dd>
              </dl>
            ) : (
              <p className="aside">Not finished yet: the questions for this place were never all answered.</p>
            )}
            <p>{plan.use.meaning}</p>
            {plan.active ? (
              <p className="plan-choice__current">
                <strong>{plan.use.label}</strong>
              </p>
            ) : (
              <button type="button" className="button" disabled={busy} onClick={() => onUse(plan.id)}>
                {plan.use.label}
              </button>
            )}
          </li>
        ))}
      </ul>
      {onStartNew ? (
        <div className="choice">
          <p>{choices.startNew.meaning}</p>
          <button type="button" className="button button--secondary" disabled={busy} onClick={onStartNew}>
            {choices.startNew.label}
          </button>
        </div>
      ) : null}
    </div>
  );
}
