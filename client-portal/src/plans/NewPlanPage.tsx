import { useRef, useState } from 'react';
import { useNavigate } from 'react-router';
import { useApi } from '../api/ApiProvider';
import { catalogue } from '../api/catalogue';
import { plans, plansIn } from '../api/plans';
import { useAction } from '../api/useAction';
import { useRemote } from '../api/useRemote';
import { Failure, Loading } from '../ui/Feedback';
import { PlanChoiceList } from './PlanChoiceList';

/** Not a city id: slugs are lowercase words joined by hyphens, so this can never collide with one. */
const UNLISTED = '__unlisted__';

/**
 * Starts a plan for a place, at any time - moving is one reason, planning for somewhere you might
 * move is another.
 *
 * When the country chosen already has plans, those come first: picking one up is usually what the
 * user meant, and a second plan for the same city would split their history for nothing. Starting a
 * new one is still one press away.
 */
export function NewPlanPage() {
  const api = useApi();
  const navigate = useNavigate();
  const [country, setCountry] = useState('');
  const [startingNew, setStartingNew] = useState(false);
  const [cityChoice, setCityChoice] = useState('');
  const [cityName, setCityName] = useState('');
  const [bringGoals, setBringGoals] = useState(true);

  const countries = useRemote('countries', (signal) => catalogue.countries(api, signal));
  const existing = useRemote(country ? `plans-${country}` : null, (signal) => plansIn(api, country, signal));
  const cities = useRemote(country ? `cities-${country}` : null, (signal) => catalogue.cities(api, country, signal));

  const use = useAction(async (planId: string) => {
    await plans.use(api, planId);
    navigate('/plan');
  });
  // Once a plan has started, this page never starts another. `useAction` refuses a second press while
  // the first request is out; this closes the moment between its answer and leaving the page, when a
  // second press would make a duplicate plan for the same place.
  const started = useRef(false);
  const start = useAction(async () => {
    if (started.current) return;
    const unlisted = cityChoice === UNLISTED;
    await plans.start(api, {
      country,
      city: unlisted ? null : cityChoice,
      cityNotListed: unlisted ? cityName.trim() : null,
      bringGoals,
    });
    started.current = true;
    // A new place has no spending, lifestyle or income yet; the setup questions ask for them.
    navigate('/setup/questions');
  });

  function pickCountry(code: string) {
    setCountry(code);
    setStartingNew(false);
    setCityChoice('');
    setCityName('');
  }

  const choices = existing.state === 'ready' ? existing.data : null;
  const offerExisting = choices !== null && choices.plans.length > 0 && !startingNew;
  const placeChosen = cityChoice !== '' && (cityChoice !== UNLISTED || cityName.trim() !== '');
  const busy = use.busy || start.busy;

  return (
    <section className="card">
      <h1>New plan</h1>
      <p className="lede">
        A plan is built for one place, because what things cost depends on where you live. Your other plans stay
        exactly as they are.
      </p>

      {countries.state === 'loading' ? <Loading what="countries" /> : null}
      {countries.state === 'failed' ? <Failure message={countries.message} retry={countries.retry} /> : null}
      {countries.state === 'ready' ? (
        <label className="field">
          <span className="field__label">Country</span>
          <span className="field__help">We only list countries we have real price figures for.</span>
          <select value={country} onChange={(e) => pickCountry(e.target.value)}>
            <option value="" disabled>
              Choose a country
            </option>
            {countries.data.map((c) => (
              <option key={c.code} value={c.code}>
                {c.name}
              </option>
            ))}
          </select>
        </label>
      ) : null}

      {country && existing.state === 'loading' ? <Loading what="your plans there" /> : null}
      {country && existing.state === 'failed' ? <Failure message={existing.message} retry={existing.retry} /> : null}

      {offerExisting ? (
        <div className="choice">
          <h2>You already have plans here</h2>
          <PlanChoiceList
            choices={choices}
            busy={busy}
            onUse={(planId) => void use.run(planId)}
            onStartNew={() => setStartingNew(true)}
          />
        </div>
      ) : null}

      {choices !== null && !offerExisting ? (
        <div className="choice">
          {cities.state === 'loading' ? <Loading what="cities" /> : null}
          {cities.state === 'failed' ? <Failure message={cities.message} retry={cities.retry} /> : null}
          {cities.state === 'ready' ? (
            <label className="field">
              <span className="field__label">City</span>
              <span className="field__help">Pick the nearest one, or choose the last option if yours is not here.</span>
              <select value={cityChoice} onChange={(e) => setCityChoice(e.target.value)}>
                <option value="" disabled>
                  Choose a city
                </option>
                {cities.data.map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.region ? `${c.name}, ${c.region}` : c.name}
                  </option>
                ))}
                <option value={UNLISTED}>My city isn't listed</option>
              </select>
            </label>
          ) : null}

          {cityChoice === UNLISTED ? (
            <label className="field">
              <span className="field__label">What is your city called?</span>
              <span className="field__help">Only shown back to you. Typical figures for the country are used.</span>
              <input value={cityName} onChange={(e) => setCityName(e.target.value)} />
            </label>
          ) : null}

          <label className="field field--check">
            <input type="checkbox" checked={bringGoals} onChange={(e) => setBringGoals(e.target.checked)} />
            <span className="field__label">{choices.startNew.bringGoalsLabel}</span>
          </label>

          <p>{choices.startNew.meaning}</p>
          {start.error ? <Failure message={start.error} /> : null}
          <div className="actions">
            <button type="button" className="button" disabled={!placeChosen || busy} onClick={() => void start.run()}>
              {choices.startNew.label}
            </button>
          </div>
        </div>
      ) : null}

      {use.error ? <Failure message={use.error} /> : null}
    </section>
  );
}
