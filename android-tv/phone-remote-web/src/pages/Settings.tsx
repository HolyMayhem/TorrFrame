import { AdditionalSettings } from "../components/AdditionalSettings";
import { CatalogNetworkSettings } from "../components/CatalogNetworkSettings";
import { t, webLanguage, setWebLanguage } from "../i18n";
import { useEffect, useState } from "react";
import { request } from "../api";
import { useAction, type Actions } from "../actions";
import type { Engine, Settings, Snapshot } from "../types";
import { Button, Icon, CollapsiblePanel as Panel, Toggle } from "../components/Ui";
export function SettingsPage({
  state,
  actions,
}: {
  state: Snapshot;
  actions: Actions;
}) {
  const [tab, setTab] = useState("app");
  return (
    <>
      <div className="page-heading">
        <small>{t("НАСТРОЙКИ ВАШЕГО TV")} </small>
        <h1>{t("Настройки")} </h1>
        <p>{t("Изменения сразу доступны на телевизоре.")} </p>
      </div>
      <div className="segmented">
        <button
          className={tab === "app" ? "selected" : ""}
          onClick={() => setTab("app")}
        >
          {t("Приложение")}{" "}
        </button>
        <button
          className={tab === "server" ? "selected" : ""}
          onClick={() => setTab("server")}
        >
          TorrServer
        </button>
      </div>
      {tab === "app" ? (
        <AppSettings state={state} actions={actions} />
      ) : (
        <ServerSettings state={state} actions={actions} />
      )}
      <div className="support-links">
        <a className="button" href="https://pay.cloudtips.ru/p/8b994ba5" target="_blank" rel="noopener noreferrer">{t("Поддержать TorrFrame", "Support TorrFrame")}</a>
        <a className="button" href="https://t.me/holymayhem" target="_blank" rel="noopener noreferrer">{t("Обратная связь", "Contact the developer")}</a>
      </div>
    </>
  );
}
function AppSettings({
  state,
  actions,
}: {
  state: Snapshot;
  actions: Actions;
}) {
  const [base, setBase] = useState(state.settings),
    [edits, setEdits] = useState<Record<string, unknown>>({}),
    [key, setKey] = useState("");
  const { run, busy, disabled } = useAction(actions);
  useEffect(() => {
    if (Object.keys(edits).length === 0 && !key) setBase(state.settings);
  }, [state.settings, edits, key]);
  const draft = { ...base, ...edits } as Settings;
  const changed = Object.keys(edits).length > 0 || key.length > 0;
  const markDraft = actions.remote.setDraftDirty;
  useEffect(() => {
    markDraft("app-settings", changed);
    return () => markDraft("app-settings", false);
  }, [changed, markDraft]);
  const edit = (name: string, value: unknown) =>
    setEdits((prev) => ({ ...prev, [name]: value }));
  const save = async () => {
    const result = await run("settings.update", {
      revision: base.revision,
      edits: { ...edits, ...(key ? { jackettKey: key } : {}) },
    });
    if (result) {
      setEdits({});
      setKey("");
      await actions.remote.refresh();
      actions.notify(t("Настройки сохранены на TV."));
    }
  };
  const move = (index: number, direction: number) => {
    const order = [...draft.metadataOrder];
    [order[index], order[index + direction]] = [
      order[index + direction],
      order[index],
    ];
    edit("metadataOrder", order);
  };
  return (
    <>
      {changed && base.revision !== state.settings.revision && (
        <div className="notice">
          {t("Настройки изменились на другом устройстве.")}{" "}
          <Button
            onClick={() => {
              setEdits({});
              setKey("");
              setBase(state.settings);
            }}
          >
            {t("Загрузить актуальные")}{" "}
          </Button>
        </div>
      )}
      <Panel title={t("Язык Phone Remote", "Phone Remote language")}>
        <label className="field">
          {t("Язык Phone Remote", "Phone Remote language")}
          <select value={webLanguage()} onChange={event => setWebLanguage(event.target.value as "ru" | "en")}>
            <option value="ru">Русский</option><option value="en">English</option>
          </select>
        </label>
      </Panel>
      <Panel title="Jackett">
        <Toggle label={t("Использовать Jackett", "Use Jackett")} checked={draft.jackettEnabled}
          disabled={disabled} onChange={value => edit("jackettEnabled", value)} />
        <label className="field">
          {t("Адрес Jackett")}{" "}
          <input
            type="url"
            placeholder="http://192.168.1.10:9117"
            value={draft.jackettUrl}
            onChange={(e) => edit("jackettUrl", e.target.value)}
            autoCapitalize="none"
            autoCorrect="off"
            spellCheck={false}
          />
        </label>
        <label className="field">
          {t("API-ключ")}{" "}
          <input
            type="password"
            autoComplete="new-password"
            placeholder={
              base.jackettKeyConfigured
                ? t("Ключ сохранён · пустое поле сохраняет его")
                : t("Введите API-ключ")
            }
            value={key}
            onChange={(e) => setKey(e.target.value)}
          />
        </label>
        <Button
          disabled={disabled || changed || !draft.jackettEnabled}
          onClick={() =>
            void run("settings.testJackett").then((result) => {
              if (result)
                actions.notify(t("Подключение к Jackett установлено."));
            })
          }
        >
          {t("Проверить сохранённое подключение")}{" "}
        </Button>
      </Panel>
      <Panel title={t("Источники метаданных")}>
        <p className="muted">
          {t(
            "Первый уверенный результат завершает поиск. Здесь тот же порядок, что на телевизоре.",
          )}{" "}
        </p>
        <div className="priority">
          {draft.metadataOrder.map((provider, index) => (
            <div key={provider}>
              <span className="priority-number">{index + 1}</span>
              <strong>
                {provider === "OMDb"
                  ? "OMDB"
                  : provider === "Kinopoisk"
                    ? t("Кинопоиск")
                    : provider}
              </strong>
              <button
                aria-label={t("Поднять {v0}", { v0: provider })}
                disabled={index === 0}
                onClick={() => move(index, -1)}
              >
                ↑
              </button>
              <button
                aria-label={t("Опустить {v0}", { v0: provider })}
                disabled={index === draft.metadataOrder.length - 1}
                onClick={() => move(index, 1)}
              >
                ↓
              </button>
            </div>
          ))}
        </div>
        <Toggle
          label={t("AniList для аниме")}
          checked={draft.aniListEnabled}
          onChange={(value) => edit("aniListEnabled", value)}
        />
        <label className="field">
          {t("Язык метаданных")}{" "}
          <select
            value={draft.language}
            onChange={(e) => edit("language", e.target.value)}
          >
            <option value="ru-RU">{t("Русский")} </option>
            <option value="en-US">{t("Английский")} </option>
          </select>
        </label>
        <Button
          kind="danger"
          disabled={disabled}
          onClick={() =>
            void actions
              .confirm(
                t("Очистить кеш метаданных?"),
                t("Сохранённые карточки фильмов остаются в медиатеке."),
              )
              .then((ok) => {
                if (ok)
                  return run("settings.clearMetadataCache", {
                    confirmed: true,
                  });
              })
          }
        >
          {t("Очистить кеш метаданных")}{" "}
        </Button>
      </Panel>
      <Panel title={t("Воспроизведение и оформление")}>
        <Toggle
          label={t("Продолжать просмотр")}
          checked={draft.resume}
          onChange={(value) => edit("resume", value)}
        />
        <div className="field-grid">
          <label className="field">
            {t("Язык аудио")}{" "}
            <input
              value={draft.audioLanguage}
              placeholder="ru / en"
              maxLength={40}
              onChange={(e) => edit("audioLanguage", e.target.value)}
            />
          </label>
          <label className="field">
            {t("Язык субтитров")}{" "}
            <input
              value={draft.subtitleLanguage}
              placeholder="ru / en"
              maxLength={40}
              onChange={(e) => edit("subtitleLanguage", e.target.value)}
            />
          </label>
        </div>
        <label className="field">
          {t("Язык TV-интерфейса")}{" "}
          <select
            value={draft.uiLanguage}
            onChange={(e) => edit("uiLanguage", e.target.value)}
          >
            <option value="ru">{t("Русский")} </option>
            <option value="en">{t("Английский")} </option>
          </select>
        </label>
      </Panel>
      <CatalogNetworkSettings value={draft.catalogNetwork} edit={edit} />
      <AdditionalSettings
        draft={draft}
        edit={edit}
        actions={actions}
        providers={state.searchProviders}
      />
      <Panel title={t("Обновления приложения")}>
        <p className="muted"><a href="https://github.com/HolyMayhem/TorrFrame/releases" target="_blank" rel="noreferrer">HolyMayhem/TorrFrame</a></p>
        <p className="muted">{t("Установка требует подтверждения на TV.", "Installation requires confirmation on the TV.")}</p>
        <div className="button-row">
          <Button
            disabled={disabled || state.updates.busy || changed}
            onClick={() => void run("updates.check")}
          >
            {t("Проверить обновления")}{" "}
          </Button>
          {state.updates.availableVersion && (
            <Button
              disabled={disabled || state.updates.busy}
              onClick={() => void run("updates.download")}
            >
              {t("Скачать")} {state.updates.availableVersion}
            </Button>
          )}
          {state.updates.downloaded && (
            <Button
              disabled={disabled}
              onClick={() =>
                void actions
                  .confirm(
                    t("Открыть установщик на TV?"),
                    t(
                      "Завершите подтверждение обновления непосредственно на телевизоре.",
                    ),
                  )
                  .then((ok) => {
                    if (ok) return run("updates.install", { confirmed: true });
                  })
              }
            >
              {t("Установить на TV")}{" "}
            </Button>
          )}
        </div>
        <p className="muted">
          {state.updates.busy
            ? t("Выполняется проверка или загрузка…")
            : state.updates.message}
        </p>
      </Panel>
      <div className="sticky-save">
        <Button
          kind="save-settings"
          disabled={disabled || !changed}
          onClick={() => void save()}
        >
          <Icon name="check" size={18} />
          {busy ? t("Сохранение…") : t("Сохранить настройки")}
        </Button>
      </div>
      <Panel title={t("Подключение Phone Remote", "Phone Remote session")}>
        <p className="muted">
          TorrFrame {state.device.appVersion}
          {t(
            ". Доступ действует 7 дней. Его можно отозвать в настройках телевизора. HTTP в локальной сети не шифрует трафик.",
          )}{" "}
        </p>
        <Button
          kind="danger"
          disabled={!actions.remote.connected}
          onClick={() =>
            void actions
              .confirm(
                t("Завершить эту сессию?"),
                t("Для нового подключения понадобится QR-код с телевизора."),
              )
              .then(async (ok) => {
                if (ok) {
                  await request("/api/logout", {}, state.csrf);
                  location.reload();
                }
              })
          }
        >
          {t("Отключить этот браузер")}{" "}
        </Button>
      </Panel>
    </>
  );
}
function ServerSettings({
  state,
  actions,
}: {
  state: Snapshot;
  actions: Actions;
}) {
  const [local, setLocal] = useState(state.settings.useLocalServer),
    [address, setAddress] = useState(state.settings.torrServerUrl),
    [port, setPort] = useState(String(state.settings.port)),
    [engine, setEngine] = useState<Engine | null>(null),
    [edits, setEdits] = useState<Record<string, string>>({}),
    [diagnosis, setDiagnosis] = useState<{
      latencyMs: number;
      speedMbps: number | null;
      failure: number | null;
    } | null>(null);
  const { run, busy, disabled } = useAction(actions);
  useEffect(() => {
    setLocal(state.settings.useLocalServer);
    setAddress(state.settings.torrServerUrl);
    setPort(String(state.settings.port));
    setEngine(null);
    setEdits({});
  }, [
    state.settings.useLocalServer,
    state.settings.torrServerUrl,
    state.settings.port,
  ]);
  const changed = local !== state.settings.useLocalServer || address !== state.settings.torrServerUrl
    || port !== String(state.settings.port) || Object.keys(edits).length > 0;
  const markDraft = actions.remote.setDraftDirty;
  useEffect(() => {
    markDraft("server-settings", changed);
    return () => markDraft("server-settings", false);
  }, [changed, markDraft]);
  const connect = async () => {
    const result = await run<{ message: string }>("server.connect", {
      revision: state.settings.revision,
      local,
      address,
      port: Number(port),
    });
    if (result) {
      actions.notify(result.message);
      await actions.remote.refresh();
    }
  };
  const load = async () => {
    const result = await run<Engine>("engine.get");
    if (result) {
      setEngine(result);
      setEdits({});
    }
  };
  const save = async () => {
    if (
      await actions.confirm(
        t("Применить параметры движка?"),
        t(
          "TorrServer пересоздаст Torrent-движок и сбросит активные раздачи, в том числе у других клиентов.",
        ),
      )
    ) {
      const result = await run<{ message: string }>("engine.save", {
        address: engine!.address,
        edits,
        base: Object.fromEntries(
          engine!.fields
            .filter((field) => field.key in edits)
            .map((field) => [field.key, field.value]),
        ),
        confirmed: true,
      });
      if (result) {
        actions.notify(result.message);
        await load();
      }
    }
  };
  const reset = async () => {
    if (
      await actions.confirm(
        t("Восстановить параметры сервера?"),
        t(
          "Сбрасываются все параметры движка, включая не показанные на экране. Активные раздачи будут сброшены.",
        ),
      )
    ) {
      const result = await run<{ message: string }>("engine.reset", {
        address: engine!.address,
        confirmed: true,
      });
      if (result) {
        actions.notify(result.message);
        await load();
      }
    }
  };
  return (
    <>
      <Panel title={t("Подключение")}>
        <div className="server-status">
          <i
            className={`dot ${state.server.connection === "Connected" ? "live" : ""}`}
          />
          <strong>
            {
              {
                Connected: t("Подключено"),
                Connecting: t("Подключение…"),
                Unavailable: t("Сервер недоступен"),
                Error: t("Ошибка соединения"),
              }[state.server.connection]
            }
          </strong>
          <small>{state.server.version}</small>
        </div>
        <Toggle
          label={t("Использовать встроенный сервер")}
          checked={local}
          onChange={setLocal}
        />
        {local ? (
          <label className="field">
            {t("Порт встроенного сервера")}{" "}
            <input
              inputMode="numeric"
              type="number"
              min="1024"
              max="65535"
              value={port}
              onChange={(e) => setPort(e.target.value)}
            />
          </label>
        ) : (
          <label className="field">
            {t("Адрес TorrServer")}{" "}
            <input
              type="url"
              value={address}
              onChange={(e) => setAddress(e.target.value)}
              autoCapitalize="none"
              spellCheck={false}
            />
          </label>
        )}
        <Button
          disabled={disabled || state.playback.active}
          kind="primary"
          onClick={() => void connect()}
        >
          {t("Сохранить подключение")}{" "}
        </Button>
        <p className="muted">
          {t(
            "HTTP / HTTPS. При смене адреса медиатека сохраняется. Нужные раздачи должны существовать на выбранном сервере.",
          )}{" "}
        </p>
      </Panel>
      {state.server.local && (
        <Panel title={t("Встроенный TorrServer")}>
          <p>
            {state.server.process === "Running"
              ? t("Сервер запущен")
              : state.server.process === "Starting"
                ? t("Сервер запускается…")
                : state.server.process === "Stopping"
                  ? t("Сервер останавливается…")
                  : state.server.process === "Error"
                    ? t("Ошибка запуска")
                    : t("Сервер остановлен")}
          </p>
          {state.server.error && <p className="notice">{state.server.error}</p>}
          <div className="button-row">
            <Button
              disabled={disabled}
              onClick={() => void run("server.start")}
            >
              {t("Запустить")}{" "}
            </Button>
            <Button
              disabled={disabled || state.playback.active}
              onClick={() =>
                void actions
                  .confirm(
                    t("Перезапустить TorrServer?"),
                    t("Активные соединения и раздачи будут прерваны."),
                  )
                  .then((ok) => {
                    if (ok) return run("server.restart", { confirmed: true });
                  })
              }
            >
              {t("Перезапустить")}{" "}
            </Button>
            <Button
              kind="danger"
              disabled={disabled || state.playback.active}
              onClick={() =>
                void actions
                  .confirm(
                    t("Остановить TorrServer?"),
                    t("Сервер останется остановленным до нового запуска."),
                  )
                  .then((ok) => {
                    if (ok) return run("server.stop", { confirmed: true });
                  })
              }
            >
              {t("Остановить")}{" "}
            </Button>
          </div>
          <Toggle
            label={t("Запускать при загрузке TV")}
            checked={state.settings.startOnBoot}
            disabled={disabled}
            onChange={(enabled) =>
              void run("server.boot", {
                enabled,
                revision: state.settings.revision,
              })
            }
          />
        </Panel>
      )}
      {state.server.local && (
        <Panel title={t("Локальная сеть", "Local network")}>
          <Toggle
            label={t(
              "Доступ к TorrServer по сети",
              "Allow LAN access to TorrServer",
            )}
            checked={state.settings.lanAccess}
            disabled={disabled || state.playback.active}
            onChange={(enabled) => {
              void actions
                .confirm(
                  t("Изменить доступ по сети?", "Change LAN access?"),
                  t(
                    "TorrServer будет перезапущен.",
                    "TorrServer will restart.",
                  ),
                )
                .then((ok) => {
                  if (ok)
                    return run("server.lan", {
                      enabled,
                      revision: state.settings.revision,
                      confirmed: true,
                    });
                });
            }}
          />
        </Panel>
      )}
      <Panel title={t("Диагностика соединения")}>
        <p className="muted">
          {t(
            "Реальная загрузка 8 МиБ с сервера. На localhost измеряется соединение внутри приставки.",
          )}{" "}
        </p>
        <div className="button-row">
          <Button disabled={disabled} onClick={() => void run("server.check")}>
            {t("Проверить доступность")}{" "}
          </Button>
          <Button
            disabled={disabled}
            onClick={() =>
              void run<typeof diagnosis>("server.diagnose").then((result) => {
                if (result) setDiagnosis(result);
              })
            }
          >
            {t("Тест скорости")}{" "}
          </Button>
        </div>
        {diagnosis && (
          <div className="metrics">
            <div>
              <strong>
                {diagnosis.latencyMs.toFixed(1)} <small>{t("мс")} </small>
              </strong>
              <span>{t("время ответа")} </span>
            </div>
            <div>
              <strong>
                {diagnosis.speedMbps !== null
                  ? diagnosis.speedMbps.toFixed(2)
                  : "—"}{" "}
                <small>{diagnosis.speedMbps !== null ? t("Мбит/с") : ""}</small>
              </strong>
              <span>
                {diagnosis.speedMbps !== null
                  ? t("средняя скорость HTTP")
                  : t("скорость не измерена")}
              </span>
            </div>
          </div>
        )}
      </Panel>
      <Panel title={t("Параметры Torrent-движка")}>
        <p className="muted">
          {t(
            "Показаны только поля, предоставленные вашим сервером. Сохранение пересоздаёт движок и сбрасывает активные раздачи.",
          )}{" "}
        </p>
        <Button disabled={disabled} onClick={() => void load()}>
          <Icon name="refresh" size={18} />
          {engine ? t("Загрузить актуальные") : t("Загрузить с сервера")}
        </Button>
        {engine && (
          <>
            <div className="engine-fields">
              {engine.fields.map((field) =>
                field.boolean ? (
                  <Toggle
                    key={field.key}
                    label={
                      webLanguage() === "en" ? field.labelEn : field.labelRu
                    }
                    checked={(edits[field.key] ?? field.value) === "true"}
                    onChange={(value) =>
                      setEdits((prev) => ({
                        ...prev,
                        [field.key]: String(value),
                      }))
                    }
                  />
                ) : (
                  <label className="field" key={field.key}>
                    {webLanguage() === "en" ? field.labelEn : field.labelRu}
                    <input
                      type="number"
                      inputMode="decimal"
                      step={field.key === "CacheSize" ? "any" : 1}
                      min={field.min}
                      max={field.max}
                      value={edits[field.key] ?? field.value}
                      onChange={(e) =>
                        setEdits((prev) => ({
                          ...prev,
                          [field.key]: e.target.value,
                        }))
                      }
                    />
                    <small>
                      {webLanguage() === "en" ? field.hintEn : field.hintRu}
                    </small>
                  </label>
                ),
              )}
            </div>
            <div className="button-row">
              <Button
                kind="primary"
                disabled={
                  disabled ||
                  state.playback.active ||
                  Object.keys(edits).length === 0
                }
                onClick={() => void save()}
              >
                {t("Сохранить на сервере")}{" "}
              </Button>
              {engine.canReset && (
                <Button
                  kind="danger"
                  disabled={disabled || state.playback.active}
                  onClick={() => void reset()}
                >
                  {t("Восстановить значения")}{" "}
                </Button>
              )}
            </div>
          </>
        )}
      </Panel>
      <Panel title={t("Обновление TorrServer")}>
        <p className="muted">
          {t(
            "Встроенный бинарник обновляется вместе с APK. Обновление внешнего сервера выполняет его владелец.",
          )}{" "}
        </p>
        <Button
          disabled={disabled || state.updates.busy}
          onClick={() => void run("updates.server")}
        >
          {t("Проверить последнюю версию")}{" "}
        </Button>
        <p className="muted">
          {state.updates.serverVersion || t("Проверка ещё не выполнялась.")}
        </p>
      </Panel>
      {busy && (
        <div className="inline-loading">
          <span className="spinner" />
          {t("Выполняется операция…")}{" "}
        </div>
      )}
    </>
  );
}
