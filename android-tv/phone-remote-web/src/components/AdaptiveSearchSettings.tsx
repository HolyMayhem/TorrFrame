import { useState } from "react";
import { useAction, type Actions } from "../actions";
import { t } from "../i18n";
import type { AdaptiveMode, AdaptiveStatus } from "../types";
import { Button, CollapsiblePanel } from "./Ui";

type Diagnostic = "Check" | "Retune" | "Compare";
interface Probe {
  ok: boolean;
  count: number;
  millis: number;
  routes: ("ByeDpi" | "Local" | "Direct")[];
  strategies: string[];
  error: "Timeout" | "Dns" | "Tls" | "Http" | "Response" | "Network" | "Proxy" | "Local" | null;
  httpStatus: number | null;
}
interface Report {
  query: string;
  firstMode: AdaptiveMode;
  unique: number;
  proxiedUnique: number | null;
  directOnly: number;
  proxyOnly: number;
  sources: { name: string; first: Probe; second: Probe | null }[];
}

const modes = () => ({
  Off: t("Выключен", "Off"),
  Auto: t("Автоматический", "Automatic"),
  Forced: t("Принудительный", "Forced"),
});

function ProbeLine({ label, probe }: { label: string; probe: Probe }) {
  const error = {
    Timeout: t("Тайм-аут", "Timed out"),
    Dns: t("Ошибка DNS", "DNS error"),
    Tls: t("Ошибка TLS", "TLS error"),
    Http: `HTTP ${probe.httpStatus}`,
    Response: t("Некорректный ответ", "Invalid response"),
    Network: t("Ошибка сети", "Network error"),
    Proxy: t("Сбой прокси", "Proxy error"),
    Local: t("Локальный адрес исключён", "Local address excluded"),
  };
  const route = {
    ByeDpi: "ByeDPI",
    Local: t("локальная сеть", "local network"),
    Direct: t("напрямую", "direct"),
  };
  const routeLabel = probe.routes.map((item) => route[item]).join(" / ") || "—";
  const result = probe.ok ? t("Доступен", "Available") : error[probe.error ?? "Network"];
  return (
    <div className="muted">
      {label} · {routeLabel} · {result} · {probe.count} {t("раздач", "torrents")} · {probe.millis} {t("мс", "ms")}
      {probe.strategies.length > 0 && <span> · {probe.strategies.join(" / ")}</span>}
    </div>
  );
}

export function AdaptiveSearchSettings({ mode, onMode, actions, state, unsaved }: {
  mode: AdaptiveMode;
  onMode: (mode: AdaptiveMode) => void;
  actions: Actions;
  state?: AdaptiveStatus;
  unsaved: boolean;
}) {
  const { run, disabled, busy } = useAction(actions);
  const [query, setQuery] = useState("");
  const [mediaType, setMediaType] = useState("Movie");
  const [report, setReport] = useState<Report>();
  const checking = busy || state?.busy === true;
  const canRun = !disabled && !checking && !unsaved && query.trim().length > 0;
  const modeLabels = modes();
  const status = {
    Idle: t("Ожидание", "Idle"),
    Starting: t("Запускается", "Starting"),
    Running: t("Работает", "Running"),
    Failed: t("Сбой прокси", "Proxy error"),
  };
  const diagnose = async (diagnostic: Diagnostic) => {
    setReport(undefined);
    const result = await run<Report>("settings.dpi.run", { query: query.trim(), mediaType, diagnostic });
    if (result) setReport(result);
  };
  return (
    <CollapsiblePanel title={t("Адаптивное подключение", "Adaptive connection")}>
      <label className="field">
        {t("Режим", "Mode")}
        <select value={mode} onChange={(event) => onMode(event.target.value as AdaptiveMode)}>
          {(Object.keys(modeLabels) as AdaptiveMode[]).map((key) => <option key={key} value={key}>{modeLabels[key]}</option>)}
        </select>
      </label>
      <p className="muted" role="status">
        ByeDPI · {state ? status[state.status] : "—"}
        {checking && ` · ${t("Проверяем источники…", "Checking sources…")}`}
      </p>
      <div className="field-grid">
        <label className="field">
          {t("Название для проверки", "Title to test")}
          <input value={query} maxLength={160} onChange={(event) => setQuery(event.target.value)} />
        </label>
        <label className="field">
          {t("Тип", "Type")}
          <select value={mediaType} onChange={(event) => setMediaType(event.target.value)}>
            <option value="Movie">{t("Фильм", "Movie")}</option>
            <option value="Series">{t("Сериал", "Series")}</option>
          </select>
        </label>
      </div>
      <div className="button-row">
        <Button disabled={!canRun} onClick={() => void diagnose("Check")}>{t("Проверить источники", "Check sources")}</Button>
        <Button disabled={!canRun} onClick={() => void diagnose("Retune")}>{t("Подобрать стратегии", "Find strategies")}</Button>
        <Button disabled={!canRun} onClick={() => void diagnose("Compare")}>{t("Сравнить поиск", "Compare search")}</Button>
        <Button disabled={disabled || checking} onClick={() => void run("settings.dpi.reset").then((result) => {
          if (result) {
            setReport(undefined);
            actions.notify(t("Сохранённые стратегии сброшены.", "Saved strategies cleared."));
          }
        })}>{t("Сбросить стратегии", "Reset strategies")}</Button>
      </div>
      {unsaved && <p className="muted">{t("Сохраните настройки перед проверкой.", "Save settings before checking sources.")}</p>}
      {report && (
        <div>
          <p><strong>{report.query}</strong></p>
          {report.proxiedUnique === null ? <p>{t("Уникальных раздач", "Unique torrents")}: {report.unique}</p> : (
            <>
              <p>{t("Уникальные раздачи", "Unique torrents")} · {t("напрямую", "direct")} {report.unique} · ByeDPI {report.proxiedUnique} · {t("разница", "difference")} {report.proxiedUnique - report.unique}</p>
              <p className="muted">{t("Только напрямую", "Direct run only")}: {report.directOnly} · {t("только во втором прогоне", "second run only")}: {report.proxyOnly}</p>
            </>
          )}
          <ul>
            {report.sources.map((source) => <li key={source.name}>
              <strong>{source.name}</strong>
              <ProbeLine label={modeLabels[report.firstMode]} probe={source.first} />
              {source.second && <ProbeLine label="ByeDPI" probe={source.second} />}
            </li>)}
          </ul>
        </div>
      )}
    </CollapsiblePanel>
  );
}
