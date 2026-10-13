import { useEffect, useState } from "react";
import { request } from "../api";
import { useAction, type Actions } from "../actions";
import type { Engine, Settings, Snapshot } from "../types";
import { Button, Icon, Panel, Toggle } from "../components/Ui";
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
        <small>НАСТРОЙКИ ВАШЕГО TV</small>
        <h1>Настройки</h1>
        <p>Изменения сразу доступны на телевизоре.</p>
      </div>
      <div className="segmented">
        <button
          className={tab === "app" ? "selected" : ""}
          onClick={() => setTab("app")}
        >
          Приложение
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
      actions.notify("Настройки сохранены на TV.");
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
          Настройки изменились на другом устройстве.
          <Button
            onClick={() => {
              setEdits({});
              setKey("");
              setBase(state.settings);
            }}
          >
            Загрузить актуальные
          </Button>
        </div>
      )}
      <Panel title="Поиск · Jackett">
        <label className="field">
          Адрес Jackett
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
          API-ключ
          <input
            type="password"
            autoComplete="new-password"
            placeholder={
              base.jackettKeyConfigured
                ? "Ключ сохранён · пустое поле сохраняет его"
                : "Введите API-ключ"
            }
            value={key}
            onChange={(e) => setKey(e.target.value)}
          />
        </label>
        <Button
          disabled={disabled || changed}
          onClick={() =>
            void run("settings.testJackett").then((result) => {
              if (result) actions.notify("Подключение к Jackett установлено.");
            })
          }
        >
          Проверить сохранённое подключение
        </Button>
      </Panel>
      <Panel title="Источники метаданных">
        <p className="muted">
          Первый уверенный результат завершает поиск. Здесь тот же порядок, что
          на телевизоре.
        </p>
        <div className="priority">
          {draft.metadataOrder.map((provider, index) => (
            <div key={provider}>
              <span className="priority-number">{index + 1}</span>
              <strong>
                {provider === "OMDb"
                  ? "IMDb через OMDb"
                  : provider === "Kinopoisk"
                    ? "Кинопоиск"
                    : provider}
              </strong>
              <button
                aria-label={`Поднять ${provider}`}
                disabled={index === 0}
                onClick={() => move(index, -1)}
              >
                ↑
              </button>
              <button
                aria-label={`Опустить ${provider}`}
                disabled={index === draft.metadataOrder.length - 1}
                onClick={() => move(index, 1)}
              >
                ↓
              </button>
            </div>
          ))}
        </div>
        <Toggle
          label="AniList для аниме"
          checked={draft.aniListEnabled}
          onChange={(value) => edit("aniListEnabled", value)}
        />
        <label className="field">
          Язык метаданных
          <select
            value={draft.language}
            onChange={(e) => edit("language", e.target.value)}
          >
            <option value="ru-RU">Русский</option>
            <option value="en-US">Английский</option>
          </select>
        </label>
        <Button
          kind="danger"
          disabled={disabled}
          onClick={() =>
            void actions
              .confirm(
                "Очистить кеш метаданных?",
                "Сохранённые карточки фильмов остаются в медиатеке.",
              )
              .then((ok) => {
                if (ok)
                  return run("settings.clearMetadataCache", {
                    confirmed: true,
                  });
              })
          }
        >
          Очистить кеш метаданных
        </Button>
      </Panel>
      <Panel title="Воспроизведение и оформление">
        <Toggle
          label="Продолжать просмотр"
          checked={draft.resume}
          onChange={(value) => edit("resume", value)}
        />
        <div className="field-grid">
          <label className="field">
            Язык аудио
            <input
              value={draft.audioLanguage}
              placeholder="ru / en"
              maxLength={40}
              onChange={(e) => edit("audioLanguage", e.target.value)}
            />
          </label>
          <label className="field">
            Язык субтитров
            <input
              value={draft.subtitleLanguage}
              placeholder="ru / en"
              maxLength={40}
              onChange={(e) => edit("subtitleLanguage", e.target.value)}
            />
          </label>
        </div>
        <label className="field">
          Язык TV-интерфейса
          <select
            value={draft.uiLanguage}
            onChange={(e) => edit("uiLanguage", e.target.value)}
          >
            <option value="ru">Русский</option>
            <option value="en">Английский</option>
          </select>
        </label>
      </Panel>
      <Panel title="Обновления приложения">
        <label className="field">
          Репозиторий GitHub
          <input
            value={draft.updateRepository}
            placeholder="владелец/репозиторий"
            maxLength={100}
            onChange={(e) => edit("updateRepository", e.target.value)}
          />
        </label>
        <p className="muted">
          Сначала сохраните репозиторий. Установка требует системного
          подтверждения на TV.
        </p>
        <div className="button-row">
          <Button
            disabled={disabled || state.updates.busy || changed}
            onClick={() => void run("updates.check")}
          >
            Проверить обновления
          </Button>
          {state.updates.availableVersion && (
            <Button
              disabled={disabled || state.updates.busy}
              onClick={() => void run("updates.download")}
            >
              Скачать {state.updates.availableVersion}
            </Button>
          )}
          {state.updates.downloaded && (
            <Button
              disabled={disabled}
              onClick={() =>
                void actions
                  .confirm(
                    "Открыть установщик на TV?",
                    "Завершите подтверждение обновления непосредственно на телевизоре.",
                  )
                  .then((ok) => {
                    if (ok) return run("updates.install", { confirmed: true });
                  })
              }
            >
              Установить на TV
            </Button>
          )}
        </div>
        <p className="muted">
          {state.updates.busy
            ? "Выполняется проверка или загрузка…"
            : state.updates.message}
        </p>
      </Panel>
      <div className="sticky-save">
        <Button
          kind="primary wide"
          disabled={disabled || !changed}
          onClick={() => void save()}
        >
          <Icon name="check" size={18} />
          {busy ? "Сохранение…" : "Сохранить настройки"}
        </Button>
      </div>
      <Panel title="Phone Remote">
        <p className="muted">
          TorrServe Silicon {state.device.appVersion}. Доступ действует 7 дней.
          Его можно отозвать в настройках телевизора. HTTP в локальной сети не
          шифрует трафик.
        </p>
        <Button
          kind="danger"
          disabled={!actions.remote.connected}
          onClick={() =>
            void actions
              .confirm(
                "Завершить эту сессию?",
                "Для нового подключения понадобится QR-код с телевизора.",
              )
              .then(async (ok) => {
                if (ok) {
                  await request("/api/logout", {}, state.csrf);
                  location.reload();
                }
              })
          }
        >
          Отключить этот браузер
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
        "Применить параметры движка?",
        "TorrServer пересоздаст Torrent-движок и сбросит активные раздачи, в том числе у других клиентов.",
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
        "Восстановить параметры сервера?",
        "Сбрасываются все параметры движка, включая не показанные на экране. Активные раздачи будут сброшены.",
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
      <Panel title="Подключение">
        <div className="server-status">
          <i
            className={`dot ${state.server.connection === "Connected" ? "live" : ""}`}
          />
          <strong>
            {
              {
                Connected: "Подключено",
                Connecting: "Подключение…",
                Unavailable: "Сервер недоступен",
                Error: "Ошибка соединения",
              }[state.server.connection]
            }
          </strong>
          <small>{state.server.version}</small>
        </div>
        <Toggle
          label="Использовать встроенный сервер"
          checked={local}
          onChange={setLocal}
        />
        {local ? (
          <label className="field">
            Порт встроенного сервера
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
            Адрес TorrServer
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
          Сохранить подключение
        </Button>
        <p className="muted">
          HTTP / HTTPS. При смене адреса медиатека сохраняется. Нужные раздачи
          должны существовать на выбранном сервере.
        </p>
      </Panel>
      {state.server.local && (
        <Panel title="Встроенный TorrServer">
          <p>
            {state.server.process === "Running"
              ? "Сервер запущен"
              : state.server.process === "Starting"
                ? "Сервер запускается…"
                : state.server.process === "Error"
                  ? "Ошибка запуска"
                  : "Сервер остановлен"}
          </p>
          {state.server.error && <p className="notice">{state.server.error}</p>}
          <div className="button-row">
            <Button
              disabled={disabled}
              onClick={() => void run("server.start")}
            >
              Запустить
            </Button>
            <Button
              disabled={disabled || state.playback.active}
              onClick={() =>
                void actions
                  .confirm(
                    "Перезапустить TorrServer?",
                    "Активные соединения и раздачи будут прерваны.",
                  )
                  .then((ok) => {
                    if (ok) return run("server.restart", { confirmed: true });
                  })
              }
            >
              Перезапустить
            </Button>
            <Button
              kind="danger"
              disabled={disabled || state.playback.active}
              onClick={() =>
                void actions
                  .confirm(
                    "Остановить TorrServer?",
                    "Сервер останется остановленным до нового запуска.",
                  )
                  .then((ok) => {
                    if (ok) return run("server.stop", { confirmed: true });
                  })
              }
            >
              Остановить
            </Button>
          </div>
          <Toggle
            label="Запускать при загрузке TV"
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
      <Panel title="Диагностика соединения">
        <p className="muted">
          Реальная загрузка 8 МиБ с сервера. На localhost измеряется соединение
          внутри приставки.
        </p>
        <div className="button-row">
          <Button disabled={disabled} onClick={() => void run("server.check")}>
            Проверить доступность
          </Button>
          <Button
            disabled={disabled}
            onClick={() =>
              void run<typeof diagnosis>("server.diagnose").then((result) => {
                if (result) setDiagnosis(result);
              })
            }
          >
            Тест скорости
          </Button>
        </div>
        {diagnosis && (
          <div className="metrics">
            <div>
              <strong>
                {diagnosis.latencyMs.toFixed(1)} <small>мс</small>
              </strong>
              <span>время ответа</span>
            </div>
            <div>
              <strong>
                {diagnosis.speedMbps !== null
                  ? diagnosis.speedMbps.toFixed(2)
                  : "—"}{" "}
                <small>{diagnosis.speedMbps !== null ? "Мбит/с" : ""}</small>
              </strong>
              <span>
                {diagnosis.speedMbps !== null
                  ? "средняя скорость HTTP"
                  : "скорость не измерена"}
              </span>
            </div>
          </div>
        )}
      </Panel>
      <Panel title="Параметры Torrent-движка">
        <p className="muted">
          Показаны только поля, предоставленные вашим сервером. Сохранение
          пересоздаёт движок и сбрасывает активные раздачи.
        </p>
        <Button disabled={disabled} onClick={() => void load()}>
          <Icon name="refresh" size={18} />
          {engine ? "Загрузить актуальные" : "Загрузить с сервера"}
        </Button>
        {engine && (
          <>
            <div className="engine-fields">
              {engine.fields.map((field) =>
                field.boolean ? (
                  <Toggle
                    key={field.key}
                    label={field.label}
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
                    {field.label}
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
                    <small>{field.hint}</small>
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
                Сохранить на сервере
              </Button>
              {engine.canReset && (
                <Button
                  kind="danger"
                  disabled={disabled || state.playback.active}
                  onClick={() => void reset()}
                >
                  Восстановить значения
                </Button>
              )}
            </div>
          </>
        )}
      </Panel>
      <Panel title="Обновление TorrServer">
        <p className="muted">
          Встроенный бинарник обновляется вместе с APK. Обновление внешнего
          сервера выполняет его владелец.
        </p>
        <Button
          disabled={disabled || state.updates.busy}
          onClick={() => void run("updates.server")}
        >
          Проверить последнюю версию
        </Button>
        <p className="muted">
          {state.updates.serverVersion || "Проверка ещё не выполнялась."}
        </p>
      </Panel>
      {busy && (
        <div className="inline-loading">
          <span className="spinner" />
          Выполняется операция…
        </div>
      )}
    </>
  );
}
