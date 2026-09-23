import { useId, type ReactNode } from 'react';

/**
 * One amount box, labelled by its question and nothing else.
 *
 * The label is tied to the input by id, not by wrapping it: a wrapping label would take in the "$"
 * and any error message too, so the input's name - what a screen reader announces and what a person
 * hears as the question - would change the moment they got an answer wrong.
 */
export function MoneyField({
  label,
  help,
  value,
  onChange,
  problem,
  unit = '$',
  unitAfter = false,
  inputMode = 'decimal',
}: {
  label: ReactNode;
  help?: ReactNode;
  value: string;
  onChange(value: string): void;
  problem?: string | null;
  unit?: string;
  unitAfter?: boolean;
  inputMode?: 'decimal' | 'numeric';
}) {
  const id = useId();
  const described = [help ? `${id}-help` : null, problem ? `${id}-problem` : null].filter(Boolean).join(' ');
  return (
    <div className="field">
      <label className="field__label" htmlFor={id}>
        {label}
      </label>
      {help ? (
        <span id={`${id}-help`} className="field__help">
          {help}
        </span>
      ) : null}
      <span className="money-input">
        {unitAfter ? null : <span aria-hidden="true">{unit}</span>}
        <input
          id={id}
          type="text"
          inputMode={inputMode}
          value={value}
          aria-invalid={problem ? true : undefined}
          aria-describedby={described || undefined}
          onChange={(e) => onChange(e.target.value)}
        />
        {unitAfter ? <span className="money-input__unit">{unit}</span> : null}
      </span>
      {problem ? (
        <span id={`${id}-problem`} className="field__error" role="alert">
          {problem}
        </span>
      ) : null}
    </div>
  );
}
