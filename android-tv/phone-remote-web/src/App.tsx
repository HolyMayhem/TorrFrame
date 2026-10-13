import { useEffect, useRef, useState } from "react";
import { useRemote } from "./useRemote";
import type { Actions } from "./actions";
import { Brand, Button, Icon, Sheet, ToastContext } from "./components/Ui";
import { FilmDetails } from "./components/FilmDetails";
import { Home } from "./pages/Home";
import { Search } from "./pages/Search";
import { Library } from "./pages/Library";
import { RemotePage } from "./pages/Remote";
import { SettingsPage } from "./pages/Settings";
const tabs = [
  ["home", "Главная"],
  ["search", "Поиск"],
  ["library", "Медиатека"],
  ["remote", "Пульт"],
  ["settings", "Настройки"],
] as const;
export function App() {
  const remote = useRemote();
  const [tab, setTab] = useState("home"),
    [itemHash, setItemHash] = useState<string | null>(null),
    [preset, setPreset] = useState(""),
    [toast, setToast] = useState(""),
    [pairCode, setPairCode] = useState(""),
    [pairBusy, setPairBusy] = useState(false),
    [pairError, setPairError] = useState(""),
    [confirmation, setConfirmation] = useState<{
      title: string;
      description: string;
      resolve: (result: boolean) => void;
    } | null>(null);
  const toastTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const notify = (text: string) => {
    setToast(text);
    if (toastTimer.current) clearTimeout(toastTimer.current);
    toastTimer.current = setTimeout(() => setToast(""), 6500);
  };
  useEffect(
    () => () => {
      if (toastTimer.current) clearTimeout(toastTimer.current);
    },
    [],
  );
  const actions: Actions = {
    remote,
    notify,
    confirm: (title, description) =>
      new Promise((resolve) =>
        setConfirmation({ title, description, resolve }),
      ),
    openItem: (hash) => {
      setItemHash(hash);
      void remote.refresh().catch((e) => notify(e.message));
    },
    searchFor: (query) => {
      setPreset(query);
      setTab("search");
    },
  };
  const chooseTab = (target: string) => {
    setTab(target);
    setItemHash(null);
    window.scrollTo({ top: 0, behavior: "instant" });
  };
  const selected = remote.state?.library.find((item) => item.hash === itemHash);
  if (!remote.state)
    return (
      <div className="pair-page">
        <Brand />
        <div className="pair-card">
          <div className="pair-visual">
            <Icon name="tv" size={76} />
            <span>
              <Icon name="remote" size={34} />
            </span>
          </div>
          <small className="eyebrow">ВАШ ТЕЛЕВИЗОР РЯДОМ</small>
          <h1>{remote.pairRequired ? "Подключимся к TV?" : "Подключаемся…"}</h1>
          <p>
            {remote.pairRequired
              ? "Откройте Phone Remote на телевизоре. Отсканируйте QR или введите временный код ниже — камера не нужна."
              : "Устанавливаем соединение с TorrServe в вашей локальной сети."}
          </p>
          {remote.error && <div className="notice">{remote.error}</div>}
          {remote.pairRequired && (
            <form className="pair-code-form" onSubmit={(event) => {
              event.preventDefault();
              if (pairBusy || pairCode.length !== 6) return;
              setPairBusy(true); setPairError("");
              void remote.pairCode(pairCode).catch((error: unknown) => {
                setPairError(error instanceof Error ? error.message : "Не удалось подключиться");
              }).finally(() => setPairBusy(false));
            }}>
              <label className="field">
                Код с экрана телевизора
                <input aria-label="Код подключения" inputMode="numeric" autoComplete="one-time-code"
                  pattern="[0-9]{6}" maxLength={12} value={pairCode}
                  placeholder="000000" required
                  onChange={(event) => setPairCode(event.target.value.replace(/[^0-9]/g, "").slice(0, 6))} />
              </label>
              <Button type="submit" kind="primary" disabled={pairBusy || pairCode.length !== 6}>
                {pairBusy ? "Подключаемся…" : "Подключиться по коду"}
              </Button>
              {pairError && <div className="notice" role="alert">{pairError}</div>}
              <small className="muted">Код одноразовый и действует 2 минуты.</small>
            </form>
          )}
          <div className="pair-steps">
            <span>
              <b>01</b>Та же сеть Wi-Fi
            </span>
            <span>
              <b>02</b>QR или код на TV
            </span>
            <span>
              <b>03</b>Готово к просмотру
            </span>
          </div>
          <small className="muted">
            Без регистрации и облака. Доступ можно отозвать на TV.
          </small>
        </div>
      </div>
    );
  return (
    <ToastContext.Provider value={toast}>
      <div className="app-shell">
        <aside className="desktop-sidebar">
          <Brand />
          <div className="sidebar-label">ВАШЕ ПРОСТРАНСТВО</div>
          <nav>
            {tabs.map(([id, label]) => (
              <button
                key={id}
                className={tab === id ? "selected" : ""}
                onClick={() => chooseTab(id)}
              >
                <Icon name={id} />
                {label}
              </button>
            ))}
          </nav>
          <div className="sidebar-device">
            <Icon name="tv" />
            <strong>{remote.state.device.name}</strong>
            <small>TorrServe Silicon {remote.state.device.appVersion}</small>
          </div>
        </aside>
        <div className="main-column">
          <header className="topbar">
            <Brand />
            <div
              className={`connection-pill ${remote.connected ? "online" : ""}`}
            >
              <i className={`dot ${remote.connected ? "live" : ""}`} />
              <span>{remote.connected ? "На связи" : "Нет связи"}</span>
            </div>
          </header>
          {!remote.connected && (
            <div className="connection-notice" role="status">
              {remote.error || "Переподключаемся к TV…"}
            </div>
          )}
          <main key={tab} className="page">
            {tab === "home" ? (
              <Home
                state={remote.state}
                actions={actions}
                navigate={chooseTab}
              />
            ) : tab === "search" ? (
              <Search actions={actions} preset={preset} />
            ) : tab === "library" ? (
              <Library state={remote.state} actions={actions} />
            ) : tab === "remote" ? (
              <RemotePage state={remote.state} actions={actions} />
            ) : (
              <SettingsPage state={remote.state} actions={actions} />
            )}
          </main>
        </div>
        <nav className="bottom-nav" aria-label="Разделы Phone Remote">
          {tabs.map(([id, label]) => (
            <button
              key={id}
              className={tab === id ? "selected" : ""}
              onClick={() => chooseTab(id)}
              aria-current={tab === id ? "page" : undefined}
            >
              <Icon name={id} />
              <span>{label}</span>
            </button>
          ))}
        </nav>
        {selected && (
          <FilmDetails
            key={selected.hash}
            item={selected}
            actions={actions}
            close={() => setItemHash(null)}
          />
        )}
        {confirmation && (
          <Sheet
            title={confirmation.title}
            close={() => {
              confirmation.resolve(false);
              setConfirmation(null);
            }}
          >
            <p className="overview">{confirmation.description}</p>
            <div className="button-row">
              <Button
                onClick={() => {
                  confirmation.resolve(false);
                  setConfirmation(null);
                }}
              >
                Отмена
              </Button>
              <Button
                kind="danger"
                onClick={() => {
                  confirmation.resolve(true);
                  setConfirmation(null);
                }}
              >
                Подтвердить
              </Button>
            </div>
          </Sheet>
        )}
        {toast && (
          <div className="toast" role="status">
            <span>{toast}</span>
            <button aria-label="Закрыть сообщение" onClick={() => setToast("")}>
              <Icon name="close" size={18} />
            </button>
          </div>
        )}
      </div>
    </ToastContext.Provider>
  );
}
