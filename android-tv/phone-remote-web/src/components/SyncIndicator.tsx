import { useState } from "react";
import { t } from "../i18n";
import type { SyncPhase } from "../useSyncStatus";
import { Icon } from "./Ui";
export function SyncIndicator({ phase, connected }: { phase: SyncPhase; connected: boolean }) {
  const [expanded, setExpanded] = useState(false);
  const state = connected ? phase : "offline";
  const label = {
    idle: t("Синхронизация с TV активна", "TV sync is active"),
    changed: t("Есть несохранённые изменения", "You have unsaved changes"),
    syncing: t("Передаём изменения на TV…", "Syncing changes to TV…"),
    done: t("Изменения сохранены на TV", "Changes saved on TV"),
    failed: t("Изменения не подтверждены. Повторите сохранение.", "Changes were not confirmed. Try saving again."),
    offline: t("Нет связи с TV. Изменения не подтверждены.", "TV is offline. Changes are not confirmed."),
  }[state];
  return <div className={`sync-indicator ${state}`}>
    <button className="icon-button" aria-label={label} title={label} aria-expanded={expanded}
      onClick={() => setExpanded(value => !value)}>
      <Icon name={state === "done" ? "check" : state === "failed" || state === "offline" ? "warning" : "refresh"} size={20} />
      {state === "changed" && <i className="sync-draft-dot" />}
    </button>
    <span className="sr-only" role="status" aria-live="polite">{label}</span>
    {expanded && <div className="sync-help">{label}</div>}
  </div>;
}
