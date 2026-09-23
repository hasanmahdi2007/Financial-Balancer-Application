/**
 * Where a number came from, shown beside the number.
 *
 * Every figure the app derives from data carries one of these, because the difference between a
 * government statistic and our own estimate is the difference between advice and a guess - and
 * hiding it would make every number look equally authoritative. All wording arrives from the API;
 * these components only lay it out.
 */

/** The short label with the sentence behind it, e.g. "Researched by us" and what that means. */
export function ProvenanceBadge({ basis, explanation }: { basis: string; explanation: string }) {
  return (
    <div className="provenance">
      <span className="provenance__badge">{basis}</span>
      <p className="provenance__explanation">{explanation}</p>
    </div>
  );
}

const DATE_WORDS = new Intl.DateTimeFormat('en-GB', {
  day: 'numeric',
  month: 'long',
  year: 'numeric',
  timeZone: 'UTC',
});

/**
 * Formats an ISO date as "1 September 2026". Parsed as a calendar date in UTC, because
 * `new Date("2026-09-01")` read in a timezone west of Greenwich is the evening of 31 August.
 */
export function formatCalendarDate(isoDate: string): string {
  const [year, month, day] = isoDate.split('-').map(Number);
  if (!year || !month || !day) return isoDate;
  return DATE_WORDS.format(new Date(Date.UTC(year, month - 1, day)));
}

/** When the figures were recorded, so a reader can judge their age for themselves. */
export function GatheredOn({ date }: { date: string }) {
  return (
    <p className="provenance__date">
      Figures gathered <time dateTime={date}>{formatCalendarDate(date)}</time>
    </p>
  );
}

/**
 * Shown when prices have probably moved since a figure was gathered. Renders nothing without
 * wording, so a screen can pass whatever the API sent and let an absent warning stay absent.
 */
export function StalenessBanner({ headline, detail }: { headline?: string | null; detail?: string | null }) {
  if (!headline) return null;
  return (
    <aside className="banner banner--warning" role="note" aria-label={headline}>
      <strong>{headline}</strong>
      {detail ? <p>{detail}</p> : null}
    </aside>
  );
}
