import { Link, Navigate, useNavigate } from 'react-router';
import { useApi } from '../api/ApiProvider';
import { useAction } from '../api/useAction';
import { saveLocation } from '../setup/saveLocation';
import { sameAmount } from '../ui/amount';
import { useOnboardingDraft } from './OnboardingDraft';

/**
 * Confirms where the user lives, and saves it to their profile when they carry on.
 *
 * Saving waits for the button rather than happening as the page opens: this page is also where
 * someone lands after pressing Back, and a save they did not ask for would overwrite a profile they
 * may have come back specifically to change.
 */
export function NextStepsPage() {
  const api = useApi();
  const navigate = useNavigate();
  const { draft } = useOnboardingDraft();
  const location = draft.location;
  const save = useAction(async () => {
    if (!location) return;
    await saveLocation(api, location);
    navigate('/setup/questions');
  });
  if (!location) return <Navigate to="/setup/location" replace />;

  const place = location.kind === 'listed' ? location.cityName : location.cityLabel;
  const changed =
    location.kind === 'unlisted'
      ? location.answers.filter((a) => !a.suggested || !sameAmount(a.amount, a.suggested)).length
      : 0;

  return (
    <section className="card">
      <p className="step">Step 1 · Where you live</p>
      <h1>{place} it is</h1>
      {location.kind === 'listed' ? (
        <p>We will compare your spending with what things cost in {place}.</p>
      ) : changed === 0 ? (
        <p>We will start from the typical figures for the country. You can change any of them later.</p>
      ) : (
        <p>
          We will use the {changed === 1 ? 'figure' : `${changed} figures`} you changed for {place}, and typical figures
          for the country everywhere else. Yours stay private to your account.
        </p>
      )}
      {save.error ? (
        <p className="field__error" role="alert">
          {save.error}
        </p>
      ) : null}
      <div className="actions">
        <Link to="/setup/location" className="button button--secondary">
          Change where you live
        </Link>
        <button type="button" className="button" disabled={save.busy} onClick={() => void save.run()}>
          {save.busy ? 'Saving…' : 'Continue'}
        </button>
      </div>
    </section>
  );
}
