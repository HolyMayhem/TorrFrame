import { useEffect, useState } from "react";
import { useAction, type Actions } from "../actions";
import type { TorrentResult } from "../types";
import { Button, Empty, Icon, Poster, size } from "../components/Ui";
export function Search({
  actions,
  preset,
}: {
  actions: Actions;
  preset: string;
}) {
  const [query, setQuery] = useState(preset),
    [filter, setFilter] = useState("All"),
    [results, setResults] = useState<TorrentResult[] | null>(null),
    [partial, setPartial] = useState(false),
    [added, setAdded] = useState<Record<string, string>>({}),
    [sort, setSort] = useState("seeds");
  const { run, busy, disabled } = useAction(actions);
  useEffect(() => {
    if (preset) setQuery(preset);
  }, [preset]);
  const search = async () => {
    if (!query.trim()) return;
    const response = await run<{ items: TorrentResult[]; partial: boolean }>(
      "search",
      { query: query.trim(), filter },
    );
    if (response) {
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
      actions.notify("Фильм добавлен в медиатеку.");
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
      <div className="page-heading">
        <small>НАХОДИТЕ И СМОТРИТЕ</small>
        <h1>Поиск</h1>
        <p>Ваши трекеры Jackett. На большом экране.</p>
      </div>
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
            placeholder="Фильм, сериал или релиз"
            aria-label="Название для поиска"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
        </label>
        <Button
          type="submit"
          kind="primary"
          disabled={disabled || !query.trim()}
        >
          {busy ? "Поиск…" : "Найти"}
        </Button>
      </form>
      <div className="chips">
        {[
          ["All", "Все"],
          ["Movies", "Фильмы"],
          ["TV Shows", "Сериалы"],
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
      {!actions.remote.state?.settings.jackettKeyConfigured ? (
        <Empty icon="search" title="Подключите Jackett">
          Укажите адрес и API-ключ в настройках приложения. Они общие для TV и
          телефона.
        </Empty>
      ) : busy && !results ? (
        <div className="loading">
          <span className="spinner" />
          Ищем на ваших трекерах…
        </div>
      ) : sorted === null ? (
        <Empty icon="search" title="Какой фильм посмотрим?">
          Введите название. Раздачи появятся после поиска.
        </Empty>
      ) : sorted.length === 0 ? (
        <Empty icon="search" title="Ничего не найдено">
          Попробуйте другое название или измените категорию.
        </Empty>
      ) : (
        <>
          <div className="results-heading">
            <span>{sorted.length} раздач</span>
            <select
              aria-label="Сортировка результатов"
              value={sort}
              onChange={(e) => setSort(e.target.value)}
            >
              <option value="seeds">Больше сидов</option>
              <option value="size">По размеру</option>
            </select>
          </div>
          {partial && (
            <p className="notice">
              Некоторые индексаторы вернули неполные результаты.
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
                    {added[result.handle] ? "В медиатеке" : "Добавить"}
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
