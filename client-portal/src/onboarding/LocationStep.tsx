import { useState } from 'react';
import { useNavigate } from 'react-router';
import { useApi } from '../api/ApiProvider';
import { catalogue, type City, type Country } from '../api/catalogue';
import { useRemote } from '../api/useRemote';
import { GatheredOn, ProvenanceBadge } from '../provenance/Provenance';
import { Failure, Loading } from '../ui/Feedback';
import { useOnboardingDraft } from './OnboardingDraft';

/**
 * Not a city id: slugs are lowercase words joined by hyphens, so this value can never collide with
 * one. It stands for the "my city isn't listed" choice inside the same dropdown.
 */
const UNLISTED = '__unlisted__';

export function LocationStep() {
  const api = useApi();
  const navigate = useNavigate();
  const { draft, chooseCountry, chooseLocation } = useOnboardingDraft();
  const countryCode = draft.countryCode ?? '';

  const [cityChoice, setCityChoice] = useState<string>(() => {
    const location = draft.location;
    if (!location) return '';
    return location.kind === 'listed' ? location.cityId : UNLISTED;
  });

  const countries = useRemote('countries', (signal) => catalogue.countries(api, signal));
  const cities = useRemote(countryCode || null, (signal) => catalogue.cities(api, countryCode, signal));

  const country: Country | undefined =
    countries.state === 'ready' ? countries.data.find((c) => c.code === countryCode) : undefined;
  const city: City | undefined =
    cities.state === 'ready' ? cities.data.find((c) => c.id === cityChoice) : undefined;

  function pickCountry(code: string) {
    chooseCountry(code);
    setCityChoice('');
  }

  function continueWithCity() {
    if (!city) return;
    chooseLocation({ kind: 'listed', countryCode, cityId: city.id, cityName: city.name });
    navigate('/setup/next');
  }

  return (
    <section className="card">
      <p className="step">Step 1 · Where you live</p>
      <h1>Where do you live?</h1>
      <p className="lede">
        Prices differ a lot from one city to the next, so we compare your spending with what things actually cost
        where you are.
      </p>

      {countries.state === 'loading' ? <Loading what="countries" /> : null}
      {countries.state === 'failed' ? <Failure message={countries.message} retry={countries.retry} /> : null}
      {countries.state === 'ready' ? (
        <label className="field">
          <span className="field__label">Country</span>
          <span className="field__help">We only list countries we have real price figures for.</span>
          <select value={countryCode} onChange={(e) => pickCountry(e.target.value)}>
            <option value="" disabled>
              Choose your country
            </option>
            {countries.data.map((c) => (
              <option key={c.code} value={c.code}>
                {c.name}
              </option>
            ))}
          </select>
        </label>
      ) : null}

      {country?.note ? (
        <p className="banner banner--info" role="note">
          {country.note}
        </p>
      ) : null}

      {countryCode ? (
        <>
          {cities.state === 'loading' ? <Loading what="cities" /> : null}
          {cities.state === 'failed' ? <Failure message={cities.message} retry={cities.retry} /> : null}
          {cities.state === 'ready' ? (
            <label className="field">
              <span className="field__label">City</span>
              <span className="field__help">
                Pick the nearest one. If yours is not here, choose the last option and tell us your costs yourself.
              </span>
              <select value={cityChoice} onChange={(e) => setCityChoice(e.target.value)}>
                <option value="" disabled>
                  Choose your city
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
        </>
      ) : null}

      {city ? (
        <div className="choice">
          <h2>{city.name}</h2>
          <ProvenanceBadge basis={city.basis} explanation={city.explanation} />
          <GatheredOn date={city.gathered} />
          <button type="button" className="button" onClick={continueWithCity}>
            Continue with {city.name}
          </button>
        </div>
      ) : null}

      {cityChoice === UNLISTED && cities.state === 'ready' ? (
        <div className="choice">
          <p>
            No problem. We will ask what you spend on a few everyday things, with typical figures
            {country ? ` for ${country.name}` : ''} already filled in. Change whatever you know is different.
          </p>
          <button
            type="button"
            className="button"
            onClick={() => navigate(`/setup/${encodeURIComponent(countryCode)}/my-city`)}
          >
            Continue
          </button>
        </div>
      ) : null}
    </section>
  );
}
