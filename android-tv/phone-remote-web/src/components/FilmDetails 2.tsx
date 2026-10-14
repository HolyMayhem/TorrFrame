import { useState } from "react";
import { useAction, type Actions } from "../actions";
import type { LibraryItem, MetadataChoice, VideoFile } from "../types";
import { Button, Icon, Panel, Poster, Sheet, size, time } from "./Ui";
export function FilmDetails({
  item,
  actions,
  close,
}: {
  item: LibraryItem;
  actions: Actions;
  close: () => void;
}) {
  const [files, setFiles] = useState<VideoFile[] | null>(null),
    [editing, setEditing] = useState(false),
    [title, setTitle] = useState(item.title),
    [query, setQuery] = useState(item.title),
    [provider, setProvider] = useState(item.metadata?.provider || "OMDb"),
    [kind, setKind] = useState(item.metadata?.kind || "Movie"),
    [choices, setChoices] = useState<MetadataChoice[] | null>(null);
  const { run, busy, disabled } = useAction(actions);
  const watch = async () => {
    const response = await run<{
      files: VideoFile[];
      preferredId: number | null;
    }>("library.files", {
      hash: item.hash,
    });
    if (response) {
      const preferred = response.files.find(
        (file) => file.id === response.preferredId,
      );
      if (preferred) await play(preferred);
      else setFiles(response.files);
    }
  };
  const play = async (file: VideoFile) => {
    const response = await run("library.play", {
      hash: item.hash,
      fileId: file.id,
    });
    if (response) {
      actions.notify("Открываем плеер на телевизоре.");
      setFiles(null);
    }
  };
  const remove = async () => {
    if (
      await actions.confirm(
        "Удалить фильм?",
        "Раздача и её история просмотра будут удалены из общей медиатеки.",
      )
    ) {
      const response = await run("library.remove", {
        hash: item.hash,
        confirmed: true,
      });
      if (response) close();
    }
  };
  const lookup = async () => {
    const response = await run<{ items: MetadataChoice[] }>("metadata.search", {
      query,
      provider,
      kind,
    });
    if (response) setChoices(response.items);
  };
  return (
    <Sheet title={editing ? "Метаданные" : item.title} close={close}>
      {!editing ? (
        <>
          <div className="film-detail">
            <Poster metadata={item.metadata} title={item.title} />
            <div>
              <span className="eyebrow">В ВАШЕЙ КОЛЛЕКЦИИ</span>
              <h2>{item.title}</h2>
              <p>
                {[
                  item.metadata?.year,
                  item.metadata?.rating
                    ? `★ ${item.metadata.rating.toFixed(1)}`
                    : null,
                  item.metadata?.runtime
                    ? `${item.metadata.runtime} мин`
                    : null,
                ]
                  .filter(Boolean)
                  .join(" · ")}
              </p>
              <small>{item.metadata?.genres}</small>
              {item.position > 0 && (
                <p className="muted">Просмотрено {time(item.position)}</p>
              )}
            </div>
          </div>
          <p className="overview">
            {item.metadata?.overview || item.sourceTitle}
          </p>
          <Button
            kind="primary wide"
            disabled={disabled}
            onClick={() => void watch()}
          >
            <Icon name="play" />{" "}
            {item.position > 30000 ? "Продолжить на TV" : "Смотреть на TV"}
          </Button>
          {files && (
            <Panel title="Выберите видео">
              {files.length ? (
                files.map((file) => (
                  <button
                    className="file-row"
                    key={file.id}
                    disabled={disabled}
                    onClick={() => void play(file)}
                  >
                    <span>{file.path}</span>
                    <small>{size(file.length)}</small>
                    <Icon name="play" size={18} />
                  </button>
                ))
              ) : (
                <p className="muted">Поддерживаемых видеофайлов не найдено.</p>
              )}
            </Panel>
          )}
          <div className="detail-actions">
            <Button
              disabled={disabled}
              onClick={() => {
                actions.searchFor(
                  item.title +
                    (item.metadata?.year ? ` ${item.metadata.year}` : ""),
                );
                close();
              }}
            >
              <Icon name="search" size={18} />
              Раздачи
            </Button>
            <Button onClick={() => setEditing(true)}>Метаданные</Button>
            <Button
              kind="danger"
              disabled={disabled}
              onClick={() => void remove()}
            >
              <Icon name="trash" size={18} />
              Удалить
            </Button>
          </div>
        </>
      ) : (
        <>
          <button className="text-button" onClick={() => setEditing(false)}>
            <Icon name="back" size={16} />
            Карточка фильма
          </button>
          <Panel title="Название в медиатеке">
            <label className="field">
              Название
              <input
                maxLength={250}
                value={title}
                onChange={(e) => setTitle(e.target.value)}
              />
            </label>
            <Button
              disabled={disabled || !title.trim()}
              onClick={() =>
                void run("metadata.rename", { hash: item.hash, title }).then(
                  (result) => {
                    if (result) actions.notify("Название сохранено.");
                  },
                )
              }
            >
              Сохранить название
            </Button>
          </Panel>
          <Panel title="Найти правильный фильм">
            <label className="field">
              Название для поиска
              <input
                maxLength={250}
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                enterKeyHint="search"
              />
            </label>
            <div className="field-grid">
              <label className="field">
                Источник
                <select
                  value={provider}
                  onChange={(e) => setProvider(e.target.value)}
                >
                  {["OMDb", "TMDB", "Kinopoisk", "AniList"].map((p) => (
                    <option key={p} value={p}>{p === "OMDb" ? "OMDB" : p}</option>
                  ))}
                </select>
              </label>
              <label className="field">
                Тип
                <select
                  value={kind}
                  onChange={(e) => setKind(e.target.value as "Movie" | "Tv")}
                >
                  <option value="Movie">Фильм</option>
                  <option value="Tv">Сериал</option>
                </select>
              </label>
            </div>
            <Button
              disabled={disabled || !query.trim()}
              onClick={() => void lookup()}
            >
              <Icon name="search" size={18} />
              {busy ? "Поиск…" : "Найти метаданные"}
            </Button>
            {choices?.length === 0 && (
              <p className="muted">Совпадений не найдено.</p>
            )}
            {choices?.map((choice) => (
              <button
                className="metadata-choice"
                key={choice.handle}
                disabled={disabled}
                onClick={() =>
                  void run("metadata.select", {
                    hash: item.hash,
                    handle: choice.handle,
                  }).then((result) => {
                    if (result) {
                      setEditing(false);
                      actions.notify("Метаданные обновлены.");
                    }
                  })
                }
              >
                <Poster
                  metadata={choice.metadata}
                  title={choice.metadata.title}
                />
                <span>
                  <strong>{choice.metadata.title}</strong>
                  <small>
                    {choice.metadata.year} · {choice.metadata.provider === "OMDb" ? "OMDB" : choice.metadata.provider}
                  </small>
                </span>
                <Icon name="check" size={18} />
              </button>
            ))}
          </Panel>
          <Panel title="Автоматическое обновление">
            <p className="muted">
              Использует общий приоритет источников из настроек. Сброс удаляет
              только метаданные карточки.
            </p>
            <div className="button-row">
              <Button
                disabled={disabled}
                onClick={() =>
                  void run<{ found: boolean }>("metadata.refresh", {
                    hash: item.hash,
                  }).then((result) => {
                    if (result)
                      actions.notify(
                        result.found
                          ? "Метаданные обновлены."
                          : "Уверенное совпадение не найдено.",
                      );
                  })
                }
              >
                Обновить
              </Button>
              <Button
                kind="danger"
                disabled={disabled}
                onClick={() =>
                  void actions
                    .confirm(
                      "Сбросить метаданные?",
                      "Постер и описание будут удалены. Фильм и история просмотра сохранятся.",
                    )
                    .then((ok) => {
                      if (ok)
                        return run("metadata.reset", {
                          hash: item.hash,
                          confirmed: true,
                        });
                    })
                }
              >
                Сбросить
              </Button>
            </div>
          </Panel>
        </>
      )}
      {busy && (
        <div className="inline-loading">
          <span className="spinner" />
          Выполняется операция…
        </div>
      )}
    </Sheet>
  );
}
