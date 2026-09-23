import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { formatCalendarDate, StalenessBanner } from './Provenance';

describe('provenance', () => {
  it('shows a gathered date as the calendar day it names, in every timezone', () => {
    // new Date('2026-09-01') is midnight UTC, which is still 31 August anywhere west of Greenwich.
    expect(formatCalendarDate('2026-09-01')).toBe('1 September 2026');
  });

  it('shows a staleness warning in the words it is given', () => {
    render(<StalenessBanner headline="Prices have probably risen" detail="These figures are ten months old." />);

    expect(screen.getByRole('note', { name: 'Prices have probably risen' })).toHaveTextContent(
      'These figures are ten months old.',
    );
  });

  it('shows no staleness warning when there is none to show', () => {
    const { container } = render(<StalenessBanner headline={null} />);

    expect(container).toBeEmptyDOMElement();
  });
});
