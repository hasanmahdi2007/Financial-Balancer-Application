import { Link, Navigate } from 'react-router';
import { useOnboardingDraft } from './OnboardingDraft';

/**
 * Where setup currently stops.
 *
 * Income, lifestyle, the share of savings the plan may use, and the dashboard itself all need
 * endpoints that do not exist yet. Rather than guess their shapes, setup says plainly that it ends
 * here for now, and keeps what the user has answered so it can be saved once they do.
 */
export function NextStepsPage() {
  const { draft } = useOnboardingDraft();
  const location = draft.location;
  if (!location) return <Navigate to="/setup/location" replace />;

  const place = location.kind === 'listed' ? location.cityName : location.cityLabel;
  return (
    <section className="card">
      <p className="step">Step 1 · Where you live</p>
      <h1>{place} it is</h1>
      {location.kind === 'listed' ? (
        <p>We will compare your spending with what things cost in {place}.</p>
      ) : (
        <p>
          We will use the {location.answers.length} figures you gave us for {place}. They stay private to your account.
        </p>
      )}
      <div className="banner banner--info" role="note">
        <strong>That is as far as setup goes for now.</strong>
        <p>
          Next come your income, how you like to live, and how much of your savings the plan should work with. Those
          steps are still being built. Your answers so far are kept in this browser tab until then.
        </p>
      </div>
      <Link to="/setup/location" className="button button--secondary">
        Change where you live
      </Link>
    </section>
  );
}
