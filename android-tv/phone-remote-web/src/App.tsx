import { t, useWebLanguage } from "./i18n";
import { useEffect, useRef, useState } from "react";
import { useRemote } from "./useRemote";
import type { Actions } from "./actions";
import { Brand, Button, Icon, Sheet, ToastContext } from "./components/Ui";
import { FilmDetails } from "./components/FilmDetails";
import type { SearchPreset } from "./types";
import { Search } from "./pages/Search";
import { Library } from "./pages/Library";
import { RemotePage } from "./pages/Remote";
import { SettingsPage } from "./pages/Settings";
import { SyncIndicator } from "./components/SyncIndicator";
import { CatalogNetworkContext } from "./components/CatalogImage";
export function App() {
  const language = useWebLanguage();
  const tabs = [
    ["search", t("Поиск")],
    ["library", t("Медиатека")],
    ["remote", t("Пульт")],
    ["settings", t("Настройки")],
  ] as const;

  const remote = useRemote();
  const [tab, setTab] = useState("search"),
    [itemHash, setItemHash] = useState<string | null>(null),
    [preset, setPreset] = useState<SearchPreset>({ query: "" }),
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
    searchFor: (query, details) => {
      setPreset({ ...details, query });
      setTab("search");
    },
  };
  const chooseTab = (target: string) => {
    setTab(target);
    setItemHash(null);
    window.scrollTo({ top: 0, behavior: "instant" });
  };
  const selected = remote.state?.library.find((item) => item.hash === itemHash || item.torrentHashes?.includes(itemHash || ""));
  useEffect(() => {
    document.documentElement.lang = language;
  }, [language]);
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
          <small className="eyebrow">{t("ВАШ ТЕЛЕВИЗОР РЯДОМ")} </small>
          <h1>
            {remote.pairRequired ? t("Подключимся к TV?") : t("Подключаемся…")}
          </h1>
          <p>
            {remote.pairRequired
              ? t(
                  "Откройте Phone Remote на телевизоре. Отсканируйте QR или введите временный код ниже — камера не нужна.",
                )
              : t(
                  "Устанавливаем соединение с TorrFrame в вашей локальной сети.",
                )}
          </p>
          {remote.error && <div className="notice">{t(remote.error)}</div>}
          {remote.pairRequired && (
            <form
              className="pair-code-form"
              onSubmit={(event) => {
                event.preventDefault();
                if (pairBusy || pairCode.length !== 6) return;
                setPairBusy(true);
                setPairError("");
                void remote
                  .pairCode(pairCode)
                  .catch((error: unknown) => {
                    setPairError(
                      error instanceof Error
                        ? error.message
                        : t("Не удалось подключиться"),
                    );
                  })
                  .finally(() => setPairBusy(false));
              }}
            >
              <label className="field">
                {t("Код с экрана телевизора")}{" "}
                <input
                  aria-label={t("Код подключения")}
                  inputMode="numeric"
                  autoComplete="one-time-code"
                  pattern="[0-9]{6}"
                  maxLength={12}
                  value={pairCode}
                  placeholder="000000"
                  required
                  onChange={(event) =>
                    setPairCode(
                      event.target.value.replace(/[^0-9]/g, "").slice(0, 6),
                    )
                  }
                />
              </label>
              <Button
                type="submit"
                kind="primary"
                disabled={pairBusy || pairCode.length !== 6}
              >
                {pairBusy ? t("Подключаемся…") : t("Подключиться по коду")}
              </Button>
              {pairError && (
                <div className="notice" role="alert">
                  {pairError}
                </div>
              )}
              <small className="muted">
                {t("Код одноразовый и действует 2 минуты.")}{" "}
              </small>
            </form>
          )}
          <div className="pair-steps">
            <span>
              <b>01</b>
              {t("Та же сеть Wi-Fi")}{" "}
            </span>
            <span>
              <b>02</b>
              {t("QR или код на TV")}{" "}
            </span>
            <span>
              <b>03</b>
              {t("Готово к просмотру")}{" "}
            </span>
          </div>
          <small className="muted">
            {t("Без регистрации и облака. Доступ можно отозвать на TV.")}{" "}
          </small>
        </div>
      </div>
    );
  return (
    <ToastContext.Provider value={toast}>
      <CatalogNetworkContext.Provider value={remote.state.settings.catalogNetwork}>
      <div className="app-shell">
        <aside className="desktop-sidebar">
          <Brand />
          <div className="sidebar-label">{t("ВАШЕ ПРОСТРАНСТВО")} </div>
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
            <small>TorrFrame {remote.state.device.appVersion}</small>
          </div>
        </aside>
        <div className="main-column">
          <header className="topbar">
            <Brand />
            <SyncIndicator connected={remote.connected} phase={remote.syncPhase} />
            <div
              className={`connection-pill ${remote.connected ? "online" : ""}`}
            >
              <i className={`dot ${remote.connected ? "live" : ""}`} />
              <span>{remote.connected ? t("На связи") : t("Нет связи")}</span>
            </div>
          </header>
          {!remote.connected && (
            <div className="connection-notice" role="status">
              {remote.error || t("Переподключаемся к TV…")}
            </div>
          )}
          <main key={tab} className="page">
            {tab === "search" ? (
              <Search
                actions={actions}
                preset={preset}
                language={language}
                revision={remote.state.catalogRevision || 0}
              />
            ) : tab === "library" ? (
              <Library state={remote.state} actions={actions} />
            ) : tab === "remote" ? (
              <RemotePage state={remote.state} actions={actions} />
            ) : (
              <SettingsPage state={remote.state} actions={actions} />
            )}
          </main>
        </div>
        <nav className="bottom-nav" aria-label={t("Разделы Phone Remote")}>
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
            title={t(confirmation.title)}
            close={() => {
              confirmation.resolve(false);
              setConfirmation(null);
            }}
          >
            <p className="overview">{t(confirmation.description)}</p>
            <div className="button-row">
              <Button
                onClick={() => {
                  confirmation.resolve(false);
                  setConfirmation(null);
                }}
              >
                {t("Отмена")}{" "}
              </Button>
              <Button
                kind="danger"
                onClick={() => {
                  confirmation.resolve(true);
                  setConfirmation(null);
                }}
              >
                {t("Подтвердить")}{" "}
              </Button>
            </div>
          </Sheet>
        )}
        {toast && (
          <div className="toast" role="status">
            <span>{t(toast)}</span>
            <button
              aria-label={t("Закрыть сообщение")}
              onClick={() => setToast("")}
            >
              <Icon name="close" size={18} />
            </button>
          </div>
        )}
      </div>
      </CatalogNetworkContext.Provider>
    </ToastContext.Provider>
  );
}
