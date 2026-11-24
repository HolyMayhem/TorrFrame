import { useEffect, useState } from "react";
import { useAction, type Actions } from "../actions";
import type { Settings, SearchOptions, PlayerPreferences, SearchProvider, JacRedMode, AdaptiveStatus } from "../types";
import { CollapsiblePanel as Panel, Toggle, Button } from "./Ui";
import { AdaptiveSearchSettings } from "./AdaptiveSearchSettings";
import { t } from "../i18n";
export function AdditionalSettings({
  draft,
  edit,
  actions,
  providers,
  jacRedKey,
  savedJacRedUrl,
  hasUnsavedChanges,
  adaptive,
}: {
  draft: Settings;
  edit: (key: string, value: unknown) => void;
  actions: Actions;
  providers: SearchProvider[];
  jacRedKey?: string;
  savedJacRedUrl: string;
  hasUnsavedChanges: boolean;
  adaptive?: AdaptiveStatus;
}) {
  const { run, disabled } = useAction(actions);
  const [players, setPlayers] = useState<
    { component: string; label: string }[]
  >([]);
  useEffect(() => {
    if (!actions.remote.connected) return;
    let active = true;
    void actions.remote
      .command<{ items: typeof players }>("settings.players")
      .then((result) => {
        if (active) setPlayers(result.items);
      })
      .catch(() => {});
    return () => {
      active = false;
    };
  }, [actions.remote.connected]);
  const p = draft.builtInPlayer,
    s = draft.torrentSearch;
  const player = (key: keyof PlayerPreferences, value: unknown) =>
    edit("builtInPlayer", { ...p, [key]: value });
  const search = (key: keyof SearchOptions, value: unknown) =>
    edit("torrentSearch", { ...s, [key]: value });
  const selectedSources = s.sources ?? providers.filter((p) => p.enabledByDefault !== false).map((p) => p.id);
  const setSource = (id: string, enabled: boolean) =>
    search("sources", enabled ? [...selectedSources, id] : selectedSources.filter((source) => source !== id));
  const hasJacRed = providers.some((p) => p.id === "jacred");
  const jacRedMode = draft.jacRedMode ?? (draft.jacRedUrl ? "Custom" : "Auto");
  const customJacRed = jacRedMode === "Custom";
  const jacRedKeyStored = draft.jacRedKeyConfigured && draft.jacRedUrl?.trim() === savedJacRedUrl;
  function select(
    label: string,
    value: string | number,
    values: [string | number, string][],
    change: (value: string) => void,
  ) {
    return (
      <label className="field">
        {label}
        <select value={value} onChange={(e) => change(e.target.value)}>
          {values.map(([key, title]) => (
            <option key={key} value={key}>
              {title}
            </option>
          ))}
        </select>
      </label>
    );
  }
  function multi(
    label: string,
    key: "resolutions" | "formats",
    values: string[],
  ) {
    return (
      <div>
        <p>{label}</p>
        <div className="chips">
          {values.map((value) => (
            <label className="check-label" key={value}>
              <input
                type="checkbox"
                checked={s[key].includes(value)}
                onChange={(e) =>
                  search(
                    key,
                    e.target.checked
                      ? [...s[key], value]
                      : s[key].filter((item) => item !== value),
                  )
                }
              />
              {value}
            </label>
          ))}
        </div>
      </div>
    );
  }
  return (
    <>
      <Panel title={t("Оформление TV", "TV appearance")}>
        <div className="field-grid">
          {select(
            t("Тема", "Theme"),
            draft.uiTheme,
            [
              ["dark", t("Тёмная", "Dark")],
              ["light", t("Светлая", "Light")],
            ],
            (value) => edit("uiTheme", value),
          )}
          {select(
            t("Акцент", "Accent"),
            draft.uiAccent,
            [
              ["green", t("Зелёный", "Green")],
              ["white", t("Белый", "White")],
              ["orange", t("Оранжевый", "Orange")],
              ["pink", t("Розовый", "Pink")],
              ["blue", t("Синий", "Blue")],
            ],
            (value) => edit("uiAccent", value),
          )}
          {select(
            t("Масштаб интерфейса", "Interface scale"),
            draft.uiScalePercent,
            [70, 75, 80, 85, 90, 95, 100, 105, 110].map((v) => [v, `${v}%`]),
            (value) => edit("uiScalePercent", Number(value)),
          )}
        </div>
        <Toggle
          label={t("Анимации интерфейса", "Interface animations")}
          checked={draft.animationsEnabled}
          onChange={(value) => edit("animationsEnabled", value)}
        />
        <Toggle
          label={t("Звуки интерфейса TV", "TV interface sounds")}
          checked={draft.uiSoundsEnabled}
          onChange={(value) => edit("uiSoundsEnabled", value)}
        />
        <Toggle
          label={t("Подсказки навигации", "Navigation hints")}
          checked={draft.showNavigationHints}
          onChange={(value) => edit("showNavigationHints", value)}
        />
      </Panel>
      <Panel title={t("Плеер", "Player")}>
        {select(
          t("Режим воспроизведения", "Playback mode"),
          draft.playerMode,
          [
            ["Internal", t("Встроенный", "Built-in")],
            ["External", t("Внешний", "External")],
            ["Ask", t("Спрашивать", "Ask each time")],
          ],
          (value) => edit("playerMode", value),
        )}
        <label className="field">
          {t("Внешний плеер", "External player")}
          <select
            value={draft.externalPlayer}
            onChange={(e) => edit("externalPlayer", e.target.value)}
          >
            <option value="">{t("Не выбран", "Not selected")}</option>
            {draft.externalPlayer &&
              !players.some((p) => p.component === draft.externalPlayer) && (
                <option value={draft.externalPlayer}>
                  {t("Недоступен", "Unavailable")}
                </option>
              )}
            {players.map((p) => (
              <option key={p.component} value={p.component}>
                {p.label}
              </option>
            ))}
          </select>
        </label>
        <div className="field-grid">
          {select(
            t("Скорость", "Speed"),
            p.speed,
            [0.5, 0.75, 1, 1.25, 1.5, 1.75, 2].map((v) => [v, `${v}×`]),
            (value) => player("speed", Number(value)),
          )}
          {select(
            t("Изображение", "Video aspect"),
            p.aspect,
            [
              ["Fit", t("Вместить", "Fit")],
              ["Zoom", t("Увеличить", "Zoom")],
              ["Fill", t("Растянуть", "Fill")],
            ],
            (value) => player("aspect", value),
          )}
          {select(
            t("Декодер аудио", "Audio decoder"),
            p.decoder,
            [
              ["Device", t("Устройство", "Device")],
              ["Ffmpeg", "FFmpeg"],
            ],
            (value) => player("decoder", value),
          )}
          {select(
            t("Размер субтитров", "Subtitle size"),
            p.subtitleSize,
            [75, 100, 125, 150, 175].map((v) => [v, `${v}%`]),
            (value) => player("subtitleSize", Number(value)),
          )}
          {select(t("Отступ субтитров снизу", "Subtitle bottom margin"), p.subtitleMargin ?? 4,
            [2, 4, 6, 8, 10, 12, 16, 20].map(v => [v, `${v}%`]), value => player("subtitleMargin", Number(value)))}
          {select(
            t("Цвет субтитров", "Subtitle color"),
            p.subtitleColor,
            [
              ["White", t("Белый", "White")],
              ["Yellow", t("Жёлтый", "Yellow")],
            ],
            (value) => player("subtitleColor", value),
          )}
        </div>
        <p className="muted">
          {t(
            "Смена декодера применяется при следующем запуске плеера.",
            "Decoder changes apply the next time the player starts.",
          )}
        </p>
        <Toggle
          label={t("Подстройка частоты кадров", "Match frame rate")}
          checked={p.matchFrameRate}
          onChange={(value) => player("matchFrameRate", value)}
        />
        <Toggle
          label={t("Пропускать тишину", "Skip silence")}
          checked={p.skipSilence}
          onChange={(value) => player("skipSilence", value)}
        />
        <Toggle
          label={t("Фон субтитров", "Subtitle background")}
          checked={p.subtitleBackground}
          onChange={(value) => player("subtitleBackground", value)}
        />
        <Toggle
          label={t("Жирные субтитры", "Bold subtitles")}
          checked={p.subtitleBold}
          onChange={(value) => player("subtitleBold", value)}
        />
        <Toggle
          label={t("Встроенный стиль субтитров", "Embedded subtitle styles")}
          checked={p.embeddedSubtitleStyle}
          onChange={(value) => player("embeddedSubtitleStyle", value)}
        />
      </Panel>
      <Panel title="TorrentSearch">
        <p>
          {t(
            "Те же источники и фильтры, что на TV.",
            "The same sources and filters as on your TV.",
          )}
        </p>
        <div className="button-row">
          <Button onClick={() => search("sources", null)}>
            {t("Все источники", "All sources")}
          </Button>
          <Button onClick={() => search("sources", [])}>
            {t("Ни одного", "None")}
          </Button>
        </div>
        <div className="chips">
          {providers.filter((provider) => provider.id !== "jacred").map((provider) => (
            <label className="check-label" key={provider.id}>
              <input
                type="checkbox"
                checked={selectedSources.includes(provider.id)}
                onChange={(e) => setSource(provider.id, e.target.checked)}
              />
              {provider.name}
            </label>
          ))}
        </div>
        {hasJacRed && (
          <>
            <Toggle label={t("Использовать JacRed", "Use JacRed")}
              checked={selectedSources.includes("jacred")}
              onChange={(value) => setSource("jacred", value)} />
            <label className="field">
              {t("Сервер JacRed", "JacRed server")}
              <select value={jacRedMode} onChange={(event) => edit("jacRedMode", event.target.value as JacRedMode)}>
                <option value="Auto">{t("Автоматически", "Automatic")}</option>
                <option value="JacBlack">jac.black</option>
                <option value="RuJacBlack">ru.jac.black</option>
                <option value="Custom">{t("Свой сервер", "Custom server")}</option>
              </select>
            </label>
            {customJacRed && (
              <>
                <label className="field">
                  {t("Адрес JacRed", "JacRed address")}
                  <input type="url" value={draft.jacRedUrl ?? ""}
                    placeholder="http://192.168.1.10:9117" autoCapitalize="none"
                    autoCorrect="off" spellCheck={false}
                    onChange={(event) => edit("jacRedUrl", event.target.value)} />
                </label>
                <label className="field">
                  {t("API-ключ JacRed (необязательно)", "JacRed API key (optional)")}
                  <input type="password" autoComplete="new-password" value={jacRedKey ?? ""}
                    placeholder={jacRedKey === ""
                      ? t("Ключ будет удалён при сохранении", "The key will be cleared on save")
                      : jacRedKeyStored ? t("Ключ сохранён", "Key saved") : undefined}
                    onChange={(event) => edit("jacRedKey", event.target.value)} />
                </label>
              </>
            )}
            <div className="button-row">
              <Button disabled={disabled || hasUnsavedChanges || (customJacRed && !draft.jacRedUrl?.trim())}
                onClick={() => void run("settings.testJacRed").then((result) => {
                  if (result) actions.notify(t("Подключение к JacRed установлено.", "Connected to JacRed."));
                })}>
                {t("Проверить сохранённое подключение", "Test saved connection")}
              </Button>
              {customJacRed && (jacRedKey || (jacRedKeyStored && jacRedKey !== "")) && (
                <Button onClick={() => edit("jacRedKey", "")}>
                  {t("Удалить ключ", "Clear key")}
                </Button>
              )}
              <Button onClick={() => {
                if (!selectedSources.includes("jacred")) setSource("jacred", true);
                edit("jacRedMode", "Auto");
                edit("jacRedUrl", "");
                edit("jacRedKey", "");
              }}>
                {t("Сбросить JacRed", "Reset JacRed")}
              </Button>
            </div>
          </>
        )}
        <AdaptiveSearchSettings mode={draft.adaptiveMode ?? "Auto"} onMode={(mode) => edit("adaptiveMode", mode)}
          actions={actions} state={adaptive} unsaved={hasUnsavedChanges} />
        {multi(t("Разрешение", "Resolution"), "resolutions", [
          "480p",
          "720p",
          "1080p",
          "2160p",
        ])}
        {multi(t("Формат раздачи", "Release format"), "formats", [
          "WEB-DL",
          "WEBRip",
          "BluRay",
          "BDRip",
          "HDTV",
          "DVD",
        ])}
        <p className="muted">
          {t(
            "Без выбранных значений — любое разрешение или формат.",
            "No selection allows any resolution or format.",
          )}
        </p>
        <div className="field-grid">
          <label className="field">
            {t("Минимальный размер, ГиБ", "Minimum size, GiB")}
            <input
              type="number"
              min={0}
              max={1024}
              step={0.5}
              value={s.minBytes / 1073741824}
              onChange={(e) =>
                search(
                  "minBytes",
                  Math.round(Number(e.target.value) * 1073741824),
                )
              }
            />
          </label>
          <label className="field">
            {t(
              "Максимальный размер, ГиБ (0 — без ограничения)",
              "Maximum size, GiB (0 — unlimited)",
            )}
            <input
              type="number"
              min={0}
              max={1024}
              step={0.5}
              value={s.maxBytes / 1073741824}
              onChange={(e) =>
                search(
                  "maxBytes",
                  Math.round(Number(e.target.value) * 1073741824),
                )
              }
            />
          </label>
          <label className="field">
            {t("Минимум сидов", "Minimum seeds")}
            <input
              type="number"
              min={0}
              max={10000}
              value={s.minSeeds}
              onChange={(e) => search("minSeeds", Number(e.target.value))}
            />
          </label>
          <label className="field">
            {t("Ожидание источника, сек", "Source timeout, seconds")}
            <input
              type="number"
              min={5}
              max={60}
              value={s.timeout / 1000}
              onChange={(e) => search("timeout", Number(e.target.value) * 1000)}
            />
          </label>
          {select(
            t("Название для поиска", "Search title"),
            s.titleMode,
            [
              ["Original", t("Оригинальное", "Original")],
              ["Russian", t("Русское", "Russian")],
              ["Both", t("Оба", "Both")],
            ],
            (value) => search("titleMode", value),
          )}
        </div>
        <Toggle
          label={t(
            "Скрывать раздачи с 0 сидов",
            "Hide torrents with zero seeds",
          )}
          checked={s.hideZero}
          onChange={(value) => search("hideZero", value)}
        />
        <Button
          onClick={() =>
            edit("torrentSearch", {
              sources: null,
              resolutions: [],
              formats: [],
              minBytes: 0,
              maxBytes: 0,
              minSeeds: 0,
              hideZero: false,
              timeout: 18000,
              titleMode: "Both",
            })
          }
        >
          {t(
            "Сбросить настройки TorrentSearch",
            "Reset TorrentSearch settings",
          )}
        </Button>
      </Panel>
    </>
  );
}
