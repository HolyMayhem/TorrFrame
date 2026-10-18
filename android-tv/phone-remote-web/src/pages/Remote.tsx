import { t } from "../i18n";
import { useEffect, useState } from "react";
import { useAction, type Actions } from "../actions";
import type { Snapshot } from "../types";
import { Button, Empty, Icon, Panel, Poster, time } from "../components/Ui";
export function RemotePage({
  state,
  actions,
}: {
  state: Snapshot;
  actions: Actions;
}) {
  const p = state.playback;
  const [seek, setSeek] = useState(p.position),
    [dragging, setDragging] = useState(false);
  const { run, disabled } = useAction(actions);
  useEffect(() => {
    if (!dragging) setSeek(p.position);
  }, [p.position, dragging]);
  const command = (command: string, args: Record<string, unknown> = {}) =>
    run("playback.command", { command, identity: p.identity, ...args });
  const commit = () => {
    if (dragging) {
      setDragging(false);
      void command("seek", { position: seek });
    }
  };
  if (!p.active)
    return (
      <>
        <div className="page-heading">
          <small>{t("УПРАВЛЕНИЕ БЕЗ ПУЛЬТА")} </small>
          <h1>{t("Пульт")} </h1>
          <p>{state.device.name}</p>
        </div>
        <Empty icon="remote" title={t("Выберите фильм")}>
          {t(
            "Откройте фильм в медиатеке и нажмите «Смотреть на TV». Пауза, перемотка и дорожки появятся здесь.",
          )}{" "}
        </Empty>
      </>
    );
  const item = state.library.find((item) =>
    p.identity.startsWith(item.hash + ":"),
  );
  return (
    <>
      <div className="page-heading">
        <small>{t("СЕЙЧАС НА TV")} </small>
        <h1>{t("Пульт")} </h1>
        <p>{state.device.name}</p>
      </div>
      <section className="remote-player">
        <div className="remote-art">
          <Poster metadata={item?.metadata} title={p.title} />
        </div>
        <h2>{p.title}</h2>
        <p className="muted">
          {p.error
            ? t("Ошибка воспроизведения")
            : p.buffering
              ? t("Буферизация…")
              : p.playing
                ? t("Воспроизводится")
                : t("На паузе")}
        </p>
        <label className="seek">
          <span className="sr-only">{t("Позиция воспроизведения")} </span>
          <input
            type="range"
            min={0}
            max={Math.max(1, p.duration)}
            step={1000}
            value={Math.min(seek, Math.max(1, p.duration))}
            disabled={disabled || !p.seekable || p.duration === 0}
            onChange={(e) => {
              setDragging(true);
              setSeek(Number(e.target.value));
            }}
            onPointerUp={commit}
            onKeyUp={commit}
          />
          <span>{time(seek)}</span>
          <span>{time(p.duration)}</span>
        </label>
        <div className="transport">
          <button
            disabled={disabled || !p.seekable}
            className="skip"
            onClick={() =>
              void command("seek", {
                position: Math.max(0, p.position - 30000),
              })
            }
          >
            ↶<small>{t("30 сек")} </small>
          </button>
          <button
            className="play-pause"
            aria-label={
              p.playWhenReady && !p.ended ? t("Пауза") : t("Продолжить")
            }
            disabled={disabled}
            onClick={() =>
              void command(p.playWhenReady && !p.ended ? "pause" : "play")
            }
          >
            <Icon
              name={p.playWhenReady && !p.ended ? "pause" : "play"}
              size={30}
            />
          </button>
          <button
            disabled={disabled || !p.seekable || p.duration === 0}
            className="skip"
            onClick={() =>
              void command("seek", {
                position: Math.min(p.duration, p.position + 30000),
              })
            }
          >
            ↷<small>{t("30 сек")} </small>
          </button>
        </div>
        <Button
          kind="danger"
          disabled={disabled}
          onClick={() =>
            void actions
              .confirm(
                t("Остановить просмотр?"),
                t(
                  "Плеер на телевизоре закроется. История просмотра сохраняется.",
                ),
              )
              .then((ok) => {
                if (ok) return command("stop", { confirmed: true });
              })
          }
        >
          {t("Остановить просмотр")}{" "}
        </Button>
      </section>
      <Panel title={t("Дорожки")}>
        <div className="field-grid">
          <label className="field">
            {t("Аудио")}{" "}
            <select
              disabled={disabled || !p.tracks.some((t) => t.type === "audio")}
              value={
                p.tracks.find((t) => t.type === "audio" && t.selected)?.id || ""
              }
              onChange={(e) => void command("track", { track: e.target.value })}
            >
              {p.tracks
                .filter((t) => t.type === "audio")
                .map((t) => (
                  <option key={t.id} value={t.id}>
                    {t.label}
                  </option>
                ))}
            </select>
          </label>
          <label className="field">
            {t("Субтитры")}{" "}
            <select
              disabled={disabled}
              value={
                p.tracks.find((t) => t.type === "subtitle" && t.selected)?.id ||
                "subtitle:off"
              }
              onChange={(e) => void command("track", { track: e.target.value })}
            >
              <option value="subtitle:off">{t("Выключены")} </option>
              {p.tracks
                .filter((t) => t.type === "subtitle")
                .map((t) => (
                  <option key={t.id} value={t.id}>
                    {t.label}
                  </option>
                ))}
            </select>
          </label>
        </div>
      </Panel>
    </>
  );
}
