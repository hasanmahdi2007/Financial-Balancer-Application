import { Icon, type IconName } from './Icon';

/** What the answer side of a split page shows before there is an answer, so it is never blank. */
export function AnswerPlaceholder({ icon, title, children }: { icon: IconName; title: string; children: string }) {
  return (
    <div className="placeholder">
      <span className="placeholder__icon">
        <Icon name={icon} size={28} />
      </span>
      <p className="placeholder__title">{title}</p>
      <p className="aside">{children}</p>
    </div>
  );
}
