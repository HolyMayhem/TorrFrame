import { t } from "../i18n";
import { Catalog } from "./Catalog";
import { useEffect, useState, useRef } from "react";
import { useAction, type Actions } from "../actions";
import type { TorrentResult, SearchPreset } from "../types";
import { Button, Empty, Icon, Poster, size } from "../components/Ui";
export function Search({
  actions,
  preset,
  language,
  revision,
}: {
  actions: Actions;
  preset: SearchPreset;
  language: "ru" | "en";
  revision: number;
}) {
  const [mode, setMode] = useState<"titles" | "torrents">(
    preset.query ? "torrents" : "titles",
  );
  const [query, setQuery] = useState(preset.query);
  useEffect(() => {
    setQuery(preset.query);
    if (preset.query) setMode("torrents");
  }, [preset]);
  return (
    <>
      <div className="page-heading">
        <h1>{t("Поиск")}</h1>
        <p>
          {t(
            "Выбирайте из подборок, ищите фильмы и раздачи.",
            "Browse collections, find titles and torrents.",
          )}
        </p>
      </div>
      <div
        className="chips"
        role="group"
        aria-label={t("Где искать", "Search in")}
      >
        <button
          className={mode === "titles" ? "selected" : ""}
          aria-pressed={mode === "titles"}
          onClick={() => setMode("titles")}
        >
          {t("Фильмы и сериалы", "Movies and series")}
        </button>
        <button
          className={mode === "torrents" ? "selected" : ""}
          aria-pressed={mode === "torrents"}
          onClick={() => setMode("torrents")}
        >
          {t("Раздачи")}
        </button>
      </div>
      {mode === "titles" ? (
        <Catalog
          actions={actions}
          language={language}
          revision={revision}
          query={query}
          setQuery={setQuery}
        />
      ) : (
        <TorrentSearch
          actions={actions}
          preset={preset}
          query={query}
          setQuery={setQuery}
        />
      )}
    </>
  );
}

function TorrentSearch({
  actions,
  preset,
  query,
  setQuery,
}: {
  actions: Actions;
  preset: SearchPreset;
  query: string;
  setQuery: (query: string) => void;
}) {
  const [source, setSource] = useState("builtin"),
    [filter, setFilter] = useState(preset.filter || "Movies"),
    [results, setResults] = useState<TorrentResult[] | null>(null),
    [partial, setPartial] = useState(false),
    [added, setAdded] = useState<Record<string, string>>({}),
    [sort, setSort] = useState("seeds");
  const { run, busy, disabled } = useAction(actions);
  const jackettEnabled = actions.remote.state?.settings.jackettEnabled === true;
  const searchVersion = useRef(0);
  useEffect(() => {
    searchVersion.current++;
    setResults(null); setPartial(false);
    if (!jackettEnabled && source === "jackett") {
      setSource("builtin");
      setFilter(current => current === "All" ? "Movies" : current);
    }
    return () => { searchVersion.current++; };
  }, [source, jackettEnabled, filter, preset, query]);
  useEffect(() => {
    setFilter(preset.filter || "Movies");
    setResults(null);
    setPartial(false);
  }, [preset]);
  const search = async () => {
    if (!query.trim() || (source === "jackett" && !jackettEnabled)) return;
    const version = ++searchVersion.current;
    const titleMatches = query.trim() === preset.query.trim();
    const response = await run<{ items: TorrentResult[]; partial: boolean }>(
      "search",
      {
        query: query.trim(),
        filter,
        source,
        originalTitle: titleMatches ? preset.originalTitle : undefined,
        year: titleMatches ? preset.year : undefined,
        tmdbId: titleMatches ? preset.tmdbId : undefined,
      },
    );
    if (response && version === searchVersion.current) {
      setResults(response.items);
      setPartial(response.partial);
    }
  };
  const add = async (result: TorrentResult) => {
    const response = await run<{ hash: string }>("library.add", {
      handle: result.handle,
    });
    if (response) {
      setAdded((a) => ({ ...a, [result.handle]: response.hash }));
      actions.notify(t("Фильм добавлен в медиатеку."));
      actions.openItem(response.hash);
    }
  };
  const sorted =
    results &&
    [...results].sort((a, b) =>
      sort === "size" ? b.size - a.size : b.seeds - a.seeds,
    );
  return (
    <>
      <label className="field">
        {t("Источник", "Source")}
        <select
          value={source}
          onChange={(e) => {
            setSource(e.target.value);
            if (e.target.value === "builtin" && filter === "All")
              setFilter("Movies");
          }}
        >
          <option value="builtin">TorrentSearch</option>
          {jackettEnabled && <option value="jackett">Jackett</option>}
        </select>
      </label>
      <form
        onSubmit={(e) => {
          e.preventDefault();
          void search();
        }}
        className="search-form"
      >
        <label className="search-input">
          <Icon name="search" />
          <input
            autoComplete="off"
            enterKeyHint="search"
            placeholder={t("Фильм, сериал или релиз")}
            aria-label={t("Название для поиска")}
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
        </label>
        <Button
          type="submit"
          kind="primary"
          disabled={disabled || !query.trim()}
        >
          {busy ? t("Поиск…") : t("Найти")}
        </Button>
      </form>
      <div className="chips">
        {[
          ...(source === "jackett" ? [["All", t("Все")]] : []),
          ["Movies", t("Фильмы")],
          ["TV Shows", t("Сериалы")],
        ].map(([value, label]) => (
          <button
            key={value}
            className={filter === value ? "selected" : ""}
            onClick={() => setFilter(value)}
          >
            {label}
          </button>
        ))}
      </div>
      {source === "jackett" &&
      !actions.remote.state?.settings.jackettKeyConfigured ? (
        <Empty icon="search" title={t("Подключите Jackett")}>
          {t(
            "Укажите адрес и API-ключ в настройках приложения. Они общие для TV и телефона.",
          )}{" "}
        </Empty>
      ) : busy && !results ? (
        <div className="loading">
          <span className="spinner" />
          {t("Ищем на ваших трекерах…")}{" "}
        </div>
      ) : sorted === null ? (
        <Empty icon="search" title={t("Какой фильм посмотрим?")}>
          {t("Введите название. Раздачи появятся после поиска.")}{" "}
        </Empty>
      ) : sorted.length === 0 ? (
        <Empty icon="search" title={t("Ничего не найдено")}>
          {t("Попробуйте другое название или измените категорию.")}{" "}
        </Empty>
      ) : (
        <>
          <div className="results-heading">
            <span>
              {sorted.length}
              {t("раздач")}{" "}
            </span>
            <select
              aria-label={t("Сортировка результатов")}
              value={sort}
              onChange={(e) => setSort(e.target.value)}
            >
              <option value="seeds">{t("Больше сидов")} </option>
              <option value="size">{t("По размеру")} </option>
            </select>
          </div>
          {partial && (
            <p className="notice">
              {t("Некоторые индексаторы вернули неполные результаты.")}{" "}
            </p>
          )}
          <div className="torrent-list">
            {sorted.map((result) => (
              <article className="torrent" key={result.handle}>
                {result.metadata && (
                  <Poster metadata={result.metadata} title={result.title} />
                )}
                <div className="torrent-content">
                  <strong>{result.metadata?.title || result.title}</strong>
                  <p>{result.title}</p>
                  <div className="torrent-stats">
                    <span className="seed">↑ {result.seeds}</span>
                    <span>{size(result.size)}</span>
                    <span>{result.tracker}</span>
                  </div>
                  <Button
                    disabled={disabled || !!added[result.handle]}
                    onClick={() => void add(result)}
                  >
                    <Icon
                      name={added[result.handle] ? "check" : "plus"}
                      size={16}
                    />
                    {added[result.handle] ? t("В медиатеке") : t("Добавить")}
                  </Button>
                </div>
              </article>
            ))}
          </div>
        </>
      )}
    </>
  );
}
