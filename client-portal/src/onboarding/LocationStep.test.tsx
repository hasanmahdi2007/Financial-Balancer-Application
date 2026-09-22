import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { FakeAuth, FakeServer, renderApp } from '../test/fakes';
import { internalWordsIn } from '../test/internalWords';
import cities from '../test/fixtures/cities-LB.json';
import countries from '../test/fixtures/countries.json';

const signedIn = () => new FakeAuth().signedInAs('maya@example.com');
const beirut = cities.find((city) => city.id === 'beirut')!;
const lebanon = countries.find((country) => country.code === 'LB')!;

async function chooseCountry(code: string) {
  const user = userEvent.setup();
  await user.selectOptions(await screen.findByLabelText(/^Country/), code);
  return user;
}

describe('choosing where you live', () => {
  it('offers only countries we hold figures for, with the caveat that comes with them', async () => {
    renderApp('/setup/location', signedIn());

    const dropdown = await screen.findByLabelText(/^Country/);
    expect(within(dropdown).getAllByRole('option').map((o) => o.textContent)).toEqual([
      'Choose your country',
      'Lebanon',
      'United States',
    ]);

    await chooseCountry('LB');

    expect(screen.getByRole('note')).toHaveTextContent(lebanon.note);
  });

  it('lists that country’s cities, and an escape hatch for everyone else', async () => {
    renderApp('/setup/location', signedIn());
    await chooseCountry('LB');

    const dropdown = await screen.findByLabelText(/^City/);
    const options = within(dropdown).getAllByRole('option').map((o) => o.textContent);
    expect(options).toContain('Beirut, Beirut Governorate');
    expect(options.at(-1)).toBe("My city isn't listed");
  });

  it('says where a chosen city’s figures came from, in words rather than a code name', async () => {
    // The honesty is the product's claim on trust: a person should always know whether they are
    // reading a government statistic or our own research.
    renderApp('/setup/location', signedIn());
    const user = await chooseCountry('LB');
    await user.selectOptions(await screen.findByLabelText(/^City/), 'beirut');

    expect(await screen.findByText(beirut.basis)).toBeInTheDocument();
    expect(screen.getByText(beirut.explanation)).toBeInTheDocument();
    expect(screen.getByText(/Figures gathered/)).toHaveTextContent('1 September 2026');
    expect(internalWordsIn(document.body.textContent ?? '')).toEqual([]);
  });

  it('carries the chosen city forward by the id the server gave it', async () => {
    renderApp('/setup/location', signedIn());
    const user = await chooseCountry('LB');
    await user.selectOptions(await screen.findByLabelText(/^City/), 'beirut');
    await user.click(await screen.findByRole('button', { name: 'Continue with Beirut' }));

    expect(await screen.findByRole('heading', { name: 'Beirut it is' })).toBeInTheDocument();
    // A typed city name never resolves to a city; the slug is the only lookup key there is.
    expect(JSON.parse(sessionStorage.getItem('fb.onboarding.draft')!).location).toEqual({
      kind: 'listed',
      countryCode: 'LB',
      cityId: 'beirut',
      cityName: 'Beirut',
    });
  });

  it('takes someone whose city is missing to the form instead', async () => {
    renderApp('/setup/location', signedIn());
    const user = await chooseCountry('LB');
    await user.selectOptions(await screen.findByLabelText(/^City/), '__unlisted__');
    await user.click(await screen.findByRole('button', { name: 'Continue' }));

    expect(screen.getByTestId('path')).toHaveTextContent('/setup/LB/my-city');
  });

  it("never shows one country's cities under another", async () => {
    // Lebanon answers only after the United States has been picked. Rendering its cities then would
    // attach Beirut's costs to a user in Wichita, and nothing on screen would say so.
    let releaseLebanon = () => {};
    const server = new FakeServer().on(
      '/api/catalogue/countries/LB/cities',
      () =>
        new Promise<Response>((resolve) => {
          releaseLebanon = () => resolve(new Response(JSON.stringify(cities), { status: 200 }));
        }),
    );
    renderApp('/setup/location', signedIn(), server);

    const user = await chooseCountry('LB');
    await user.selectOptions(screen.getByLabelText(/^Country/), 'US');
    await screen.findByLabelText(/^City/);
    releaseLebanon();

    await waitFor(() =>
      expect(within(screen.getByLabelText(/^City/)).getAllByRole('option').map((o) => o.textContent)).toContain(
        'Wichita, Kansas',
      ),
    );
    expect(screen.queryByText('Beirut, Beirut Governorate')).not.toBeInTheDocument();
  });

  it('explains a failure and offers to try again, rather than showing an empty dropdown', async () => {
    // The network on this machine drops for seconds at a time; a blank page reads as "no countries".
    const server = new FakeServer().on('/api/catalogue/countries', {
      status: 503,
      body: { title: 'Service Unavailable', detail: 'The figures are briefly unavailable.' },
    });
    renderApp('/setup/location', signedIn(), server);

    expect(await screen.findByRole('alert')).toHaveTextContent('The figures are briefly unavailable.');

    server.on('/api/catalogue/countries', { status: 200, body: countries });
    await userEvent.setup().click(screen.getByRole('button', { name: 'Try again' }));

    expect(await screen.findByLabelText(/^Country/)).toBeInTheDocument();
  });
});
