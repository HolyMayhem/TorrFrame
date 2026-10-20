import { useContext, useEffect, useState } from "react";
import type { Actions } from "../actions";
import type { CatalogItem, CatalogPage } from "../types";
import { Button, Panel, Sheet } from "../components/Ui";
import { t } from "../i18n";
import { CatalogImage, CatalogNetworkContext } from "../components/CatalogImage";
const filters = [
  ["All", "Все", "All"],
  ["Movies", "Фильмы", "Movies"],
  ["Series", "Сериалы", "Series"],
  ["ActionAdventure", "Боевики и приключения", "Action & adventure"],
  ["Comedy", "Комедия", "Comedy"],
  ["Drama", "Драма", "Drama"],
  ["ScienceFictionFantasy", "Фантастика и фэнтези", "Sci-fi & fantasy"],
  ["Thriller", "Триллер", "Thriller"],
  ["Horror", "Ужасы", "Horror"],
  ["Animation", "Мультфильмы", "Animation"],
  ["Crime", "Криминал", "Crime"],
  ["Mystery", "Детектив", "Mystery"],
  ["Family", "Семейные", "Family"],
  ["Documentary", "Документальные", "Documentary"],
  ["Romance", "Мелодрамы", "Romance"],
];
const sections = [
  ["TrendingMovies", "В тренде: фильмы", "Trending movies"],
  ["NewMovies", "Новинки кино", "New movies"],
  ["PopularMovies", "Популярные фильмы", "Popular movies"],
  ["TopRated", "Лучшие фильмы", "Top-rated movies"],
  ["PopularTv", "Популярные сериалы", "Popular series"],
  ["TrendingTv", "В тренде: сериалы", "Trending series"],
];
export function Catalog({
  actions,
  language,
  revision,
  query,
  setQuery,
}: {
  actions: Actions;
  language: "ru" | "en";
  revision: number;
  query: string;
  setQuery: (query: string) => void;
}) {
  const [submitted, setSubmitted] = useState(query.trim());
  const [filter, setFilter] = useState("All"),
    [section, setSection] = useState("TrendingMovies");
  const [kind, setKind] = useState("Movie"),
    [page, setPage] = useState(1),
    [list, setList] = useState("");
  const [data, setData] = useState<CatalogPage | null>(null),
    [lists, setLists] = useState<CatalogItem[]>([]);
  const [selected, setSelected] = useState<CatalogItem | null>(null),
    [details, setDetails] = useState<CatalogItem | null>(null);
  const [related, setRelated] = useState<CatalogItem[]>([]),
    [relatedSource, setRelatedSource] = useState("Similar");
  const [relatedPage, setRelatedPage] = useState(1),
    [relatedTotal, setRelatedTotal] = useState(1);
  const [busy, setBusy] = useState(false),
    [failure, setFailure] = useState("");
  const [retry, setRetry] = useState(0);
  const locale = language === "ru" ? "ru-RU" : "en-US";
  const preferences = useContext(CatalogNetworkContext);
  const configuration = JSON.stringify(preferences ? { ...preferences, showFavorites: true, showWatchLater: true } : {});
  useEffect(() => {
    if (!actions.remote.connected) return;
    let active = true;
    void actions.remote
      .command<{ items: CatalogItem[] }>("catalog.lists", { language: locale })
      .then((result) => {
        if (active) setLists(result.items);
      })
      .catch((error) => {
        if (active) setFailure(error.message);
      });
    return () => {
      active = false;
    };
  }, [locale, revision, retry, configuration, actions.remote.connected]);
  useEffect(() => {
    if (!actions.remote.connected) return;
    let active = true;
    setBusy(true);
    setFailure("");
    setData(null);
    void actions.remote
      .command<CatalogPage>("catalog.browse", {
        query: submitted,
        filter,
        section,
        mediaType: kind,
        page,
        language: locale,
      })
      .then((result) => {
        if (active) setData(result);
      })
      .catch((error) => {
        if (active) setFailure(error.message);
      })
      .finally(() => {
        if (active) setBusy(false);
      });
    return () => {
      active = false;
    };
  }, [
    submitted,
    filter,
    section,
    kind,
    page,
    locale,
    retry,
    configuration,
    actions.remote.connected,
  ]);
  useEffect(() => {
    if (!selected || !actions.remote.connected) return;
    let active = true;
    setDetails(null);
    setRelated([]);
    setFailure("");
    void actions.remote
      .command<CatalogItem>("catalog.details", {
        id: selected.id,
        mediaType: selected.mediaType,
        language: locale,
      })
      .then((result) => {
        if (active) setDetails(result);
      })
      .catch((error) => {
        if (active) setFailure(error.message);
      });
    return () => {
      active = false;
    };
  }, [selected?.key, locale, retry, configuration, actions.remote.connected]);
  useEffect(() => {
    if (!selected || !actions.remote.connected) return;
    let active = true;
    void actions.remote
      .command<CatalogPage>("catalog.related", {
        id: selected.id,
        mediaType: selected.mediaType,
        source: relatedSource,
        page: relatedPage,
        language: locale,
      })
      .then((result) => {
        if (active) {
          setRelated(result.items);
          setRelatedTotal(result.totalPages);
        }
      })
      .catch((error) => {
        if (active) setFailure(error.message);
      });
    return () => {
      active = false;
    };
  }, [
    selected?.key,
    relatedSource,
    relatedPage,
    locale,
    retry,
    configuration,
    actions.remote.connected,
  ]);
  const open = (item: CatalogItem) => {
    setSelected(item);
    setRelatedPage(1);
  };
  const card = (item: CatalogItem) => (
    <button className="catalog-card" key={item.key} onClick={() => open(item)}>
      {item.poster ? (
        <CatalogImage src={item.poster} />
      ) : (
        <div className="catalog-placeholder">{item.title}</div>
      )}
      <strong>{item.title}</strong>
      <small>
        {item.year} · ★ {item.rating.toFixed(1)}
      </small>
    </button>
  );
  const toggle = async (target: "Favorites" | "WatchLater") => {
    if (!details || busy) return;
    setBusy(true);
    try {
      const result = await actions.remote.command<{ items: CatalogItem[] }>(
        "catalog.toggle",
        {
          id: details.id,
          mediaType: details.mediaType,
          list: target,
          language: locale,
        },
      );
      setLists(result.items);
    } catch (error) {
      actions.notify(
        error instanceof Error ? error.message : t("Операция не выполнена."),
      );
    } finally {
      setBusy(false);
    }
  };
  const currentList = lists.find((item) => item.key === selected?.key);
  const items = list
    ? lists.filter((item) =>
        list === "Favorites" ? item.favorite : item.watchLater,
      )
    : data?.items || [];
  const genres = !["All", "Movies", "Series"].includes(filter);
  return (
    <>
      <form
        className="search-form"
        onSubmit={(e) => {
          e.preventDefault();
          setSubmitted(query.trim());
          setPage(1);
          setList("");
        }}
      >
        <input
          aria-label={t("Название для поиска")}
          value={query}
          maxLength={250}
          placeholder={t("Фильм, сериал или релиз")}
          onChange={(e) => setQuery(e.target.value)}
        />
        <Button type="submit" disabled={!actions.remote.connected}>
          {t("Найти")}
        </Button>
      </form>
      <div className="chips">
        <Button
          onClick={() => {
            setList("");
            setSubmitted("");
            setQuery("");
          }}
        >
          {t("Обзор", "Browse")}
        </Button>
        <Button onClick={() => setList("Favorites")}>
          {t("Избранное", "Favorites")}
        </Button>
        <Button onClick={() => setList("WatchLater")}>
          {t("Смотреть позже", "Watch later")}
        </Button>
      </div>
      {!list && !submitted && (
        <div className="field-grid">
          <label className="field">
            {t("Жанр", "Genre")}
            <select
              value={filter}
              onChange={(e) => {
                setFilter(e.target.value);
                setPage(1);
                setKind("Movie");
                setSection(
                  e.target.value === "Series" ? "TrendingTv" : "TrendingMovies",
                );
              }}
            >
              {filters.map(([id, ru, en]) => (
                <option value={id} key={id}>
                  {t(ru, en)}
                </option>
              ))}
            </select>
          </label>
          {genres ? (
            <label className="field">
              {t("Тип")}
              <select
                value={kind}
                onChange={(e) => {
                  setKind(e.target.value);
                  setPage(1);
                }}
              >
                <option value="Movie">{t("Фильмы")}</option>
                {[
                  "ActionAdventure",
                  "Comedy",
                  "Drama",
                  "ScienceFictionFantasy",
                  "Animation",
                  "Crime",
                  "Mystery",
                  "Family",
                  "Documentary",
                ].includes(filter) && (
                  <option value="Tv">{t("Сериалы")}</option>
                )}
              </select>
            </label>
          ) : (
            <label className="field">
              {t("Подборка", "Collection")}
              <select
                value={section}
                onChange={(e) => {
                  setSection(e.target.value);
                  setPage(1);
                }}
              >
                {sections
                  .filter(
                    ([id]) =>
                      filter === "All" ||
                      (filter === "Series"
                        ? id.endsWith("Tv")
                        : !id.endsWith("Tv")),
                  )
                  .map(([id, ru, en]) => (
                    <option value={id} key={id}>
                      {t(ru, en)}
                    </option>
                  ))}
              </select>
            </label>
          )}
        </div>
      )}
      {!list && !submitted && filter === "All" && <>
        {([
          ["Favorites", t("Избранное", "Favorites"), preferences?.showFavorites ?? true],
          ["WatchLater", t("Смотреть позже", "Watch later"), preferences?.showWatchLater ?? true],
        ] as const).filter(([, , enabled]) => enabled).map(([id, title]) => {
          const saved = lists.filter(item => id === "Favorites" ? item.favorite : item.watchLater);
          return <Panel key={id} title={title}>
            {saved.length ? <div className="catalog-grid">{saved.slice(0, 6).map(card)}</div>
              : <p className="muted">{t("В подборке пока нет фильмов.", "No titles in this collection yet.")}</p>}
            {saved.length > 6 && <Button onClick={() => setList(id)}>{t("Показать все", "Show all")}</Button>}
          </Panel>;
        })}
      </>}
      {failure && (
        <div className="notice">
          {failure}
          <Button onClick={() => setRetry((value) => value + 1)}>
            {t("Повторить", "Retry")}
          </Button>
        </div>
      )}
      {data?.partial && (
        <p className="notice">
          {t(
            "Показаны сохранённые данные. Не удалось обновить подборку.",
            "Showing cached data. The collection could not be refreshed.",
          )}
        </p>
      )}
      {busy && <p role="status">{t("Загрузка…", "Loading…")}</p>}
      <div className="catalog-grid">{items.map(card)}</div>
      {!busy && !items.length && <p>{t("Ничего не найдено")}</p>}
      {!list && (
        <div className="button-row">
          <Button
            disabled={page <= 1 || busy}
            onClick={() => setPage(page - 1)}
          >
            {t("Назад", "Previous")}
          </Button>
          <span>
            {page} / {data?.totalPages || 1}
          </span>
          <Button
            disabled={!data || page >= data.totalPages || busy}
            onClick={() => setPage(page + 1)}
          >
            {t("Далее", "Next")}
          </Button>
        </div>
      )}
      {selected && (
        <Sheet
          title={details?.title || selected.title}
          close={() => setSelected(null)}
        >
          {details ? (
            <>
              {details.partial && <p role="status">{t("Полные сведения временно недоступны. Показаны сохранённые данные.", "Full details are temporarily unavailable. Showing saved information.")}</p>}
              <p className="overview">{details.overview}</p>
              <p>
                {details.year} · {details.genres?.join(" · ")} ·{" "}
                {details.runtime || "—"} {t("мин", "min")}
              </p>
              <p>{details.directors?.join(", ")}</p>
              <p>{details.cast?.join(", ")}</p>
              {details.seasons && (
                <p>
                  {t("Сезоны", "Seasons")}: {details.seasons} ·{" "}
                  {t("Эпизоды", "Episodes")}: {details.episodes}
                </p>
              )}
              <div className="button-row">
                <Button
                  kind="primary"
                  onClick={() =>
                    actions.searchFor(details.title, {
                      originalTitle: details.originalTitle,
                      year: details.year,
                      tmdbId: details.id,
                      filter:
                        details.mediaType === "Tv" ? "TV Shows" : "Movies",
                    })
                  }
                >
                  {t("Раздачи")}
                </Button>
                <Button
                  disabled={busy}
                  onClick={() => void toggle("Favorites")}
                >
                  {currentList?.favorite ? "✓ " : "+ "}
                  {t("Избранное", "Favorites")}
                </Button>
                <Button
                  disabled={busy}
                  onClick={() => void toggle("WatchLater")}
                >
                  {currentList?.watchLater ? "✓ " : "+ "}
                  {t("Смотреть позже", "Watch later")}
                </Button>
              </div>
              <Panel
                title={t(
                  "Похожие и рекомендации",
                  "Similar titles and recommendations",
                )}
              >
                <select
                  value={relatedSource}
                  onChange={(e) => {
                    setRelatedSource(e.target.value);
                    setRelatedPage(1);
                  }}
                >
                  <option value="Similar">{t("Похожие", "Similar")}</option>
                  <option value="Recommendations">
                    {t("Рекомендации", "Recommendations")}
                  </option>
                </select>
                <div className="catalog-grid">{related.map(card)}</div>
                <div className="button-row">
                  <Button
                    disabled={relatedPage <= 1}
                    onClick={() => setRelatedPage(relatedPage - 1)}
                  >
                    {t("Назад", "Previous")}
                  </Button>
                  <Button
                    disabled={relatedPage >= relatedTotal}
                    onClick={() => setRelatedPage(relatedPage + 1)}
                  >
                    {t("Далее", "Next")}
                  </Button>
                </div>
              </Panel>
            </>
          ) : (
            <p>{t("Загрузка…", "Loading…")}</p>
          )}
        </Sheet>
      )}
    </>
  );
}
