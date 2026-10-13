import {
  useEffect,
  createContext,
  useContext,
  useRef,
  type ReactNode,
} from "react";
import type { LibraryItem, Metadata } from "../types";
export const ToastContext = createContext("");
export const icons = {
  home: "M3 10L12 3l9 7v11h-6v-7H9v7H3z",
  search: "M21 21l-6-6M17 10a7 7 0 1 1-14 0 7 7 0 0 1 14 0",
  library: "M3 3h7v7H3zM14 3h7v7h-7zM3 14h7v7H3zM14 14h7v7h-7z",
  remote: "M6 2h12v20H6zM9 6h6M9 17h6",
  settings:
    "M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8M9 3h6l1 3 3 1 2 5-2 5-3 1-1 3H9l-1-3-3-1-2-5 2-5 3-1z",
  play: "M8 4l12 8-12 8z",
  pause: "M7 4v16M17 4v16",
  plus: "M12 5v14M5 12h14",
  chevron: "M9 5l7 7-7 7",
  close: "M6 6l12 12M18 6L6 18",
  refresh: "M20 7v5h-5M4 17v-5h5M19 12a7 7 0 0 0-12-5M5 12a7 7 0 0 0 12 5",
  tv: "M3 5h18v13H3zM8 22l4-4 4 4",
  check: "M5 12l4 4 10-10",
  back: "M15 5l-7 7 7 7",
  trash: "M3 6h18M8 6V3h8v3M6 6l1 15h10l1-15M10 10v7M14 10v7",
  logout: "M10 4H4v16h6M13 12h8M17 8l4 4-4 4",
};
export function Icon({
  name,
  size = 22,
}: {
  name: keyof typeof icons;
  size?: number;
}) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.7"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <path d={icons[name]} />
    </svg>
  );
}
export function Brand() {
  return (
    <div className="brand">
      <img src="/app-icon.png" width="36" height="36" alt="" />
      <div>
        <strong>TorrServe</strong>
        <small>Silicon · Phone Remote</small>
      </div>
    </div>
  );
}
export function Button({
  children,
  onClick,
  disabled = false,
  kind = "",
  type = "button",
}: {
  children: ReactNode;
  onClick?: () => void;
  disabled?: boolean;
  kind?: string;
  type?: "button" | "submit";
}) {
  return (
    <button
      type={type}
      className={`button ${kind}`}
      onClick={onClick}
      disabled={disabled}
    >
      {children}
    </button>
  );
}
export function Empty({
  title,
  children,
  icon = "library",
}: {
  title: string;
  children: ReactNode;
  icon?: keyof typeof icons;
}) {
  return (
    <div className="empty">
      <div className="empty-icon">
        <Icon name={icon} size={32} />
      </div>
      <h3>{title}</h3>
      <p>{children}</p>
    </div>
  );
}
export function Sheet({
  title,
  children,
  close,
}: {
  title: string;
  children: ReactNode;
  close: () => void;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  const notice = useContext(ToastContext);
  useEffect(() => {
    ref.current?.showModal();
    return () => ref.current?.close();
  }, []);
  return (
    <dialog
      ref={ref}
      className="sheet"
      onCancel={close}
      onClick={(e) => {
        if (e.target === e.currentTarget) close();
      }}
    >
      <div className="sheet-content">
        <div className="sheet-header">
          <h2>{title}</h2>
          <button
            className="icon-button"
            aria-label="Закрыть"
            onClick={close}
            autoFocus
          >
            <Icon name="close" />
          </button>
        </div>
        {notice && (
          <div className="sheet-notice" role="status">
            {notice}
          </div>
        )}
        {children}
      </div>
    </dialog>
  );
}
export function Poster({
  metadata,
  title,
}: {
  metadata?: Metadata | null;
  title: string;
}) {
  const ref = useRef<HTMLImageElement>(null);
  useEffect(() => {
    if (ref.current) ref.current.style.display = "";
  }, [metadata?.poster]);
  return (
    <div className="poster">
      <span>{title.slice(0, 1)}</span>
      {metadata?.poster && (
        <img
          ref={ref}
          src={metadata.poster}
          loading="lazy"
          decoding="async"
          alt=""
          referrerPolicy="no-referrer"
          onError={(e) => {
            e.currentTarget.style.display = "none";
          }}
        />
      )}
    </div>
  );
}
export function FilmCard({
  item,
  open,
}: {
  item: LibraryItem;
  open: () => void;
}) {
  return (
    <button className="film-card" onClick={open}>
      <Poster metadata={item.metadata} title={item.title} />
      <strong>{item.title}</strong>
      <small>
        {[
          item.metadata?.year,
          item.metadata?.rating ? `★ ${item.metadata.rating.toFixed(1)}` : null,
        ]
          .filter(Boolean)
          .join(" · ") || "В медиатеке"}
      </small>
      {item.duration > 0 && item.position > 0 && (
        <div className="progress">
          <i
            style={{
              width: `${Math.min(100, (100 * item.position) / item.duration)}%`,
            }}
          />
        </div>
      )}
    </button>
  );
}
export function Toggle({
  label,
  checked,
  onChange,
  disabled = false,
}: {
  label: string;
  checked: boolean;
  onChange: (value: boolean) => void;
  disabled?: boolean;
}) {
  return (
    <label className="toggle">
      <span>{label}</span>
      <input
        type="checkbox"
        checked={checked}
        disabled={disabled}
        onChange={(e) => onChange(e.target.checked)}
      />
      <span className="switch" aria-hidden="true" />
    </label>
  );
}
export function Panel({
  title,
  children,
}: {
  title: string;
  children: ReactNode;
}) {
  return (
    <section className="panel">
      <h3>{title}</h3>
      {children}
    </section>
  );
}
export const time = (ms: number) => {
  const s = Math.max(0, Math.floor(ms / 1000));
  return `${Math.floor(s / 3600) ? `${Math.floor(s / 3600)}:` : ""}${String(Math.floor(s / 60) % 60).padStart(2, "0")}:${String(s % 60).padStart(2, "0")}`;
};
export const size = (bytes: number) =>
  bytes >= 1073741824
    ? `${(bytes / 1073741824).toFixed(1)} ГБ`
    : `${(bytes / 1048576).toFixed(0)} МБ`;
