import { useState } from "react";
import { useAction, type Actions } from "../actions";
import type { Snapshot } from "../types";
import { Button, Empty, FilmCard, Icon } from "../components/Ui";
export function Library({
  state,
  actions,
}: {
  state: Snapshot;
  actions: Actions;
}) {
  const [query, setQuery] = useState("");
  const { run, disabled, busy } = useAction(actions);
  const items = state.library.filter((item) =>
    item.title.toLocaleLowerCase().includes(query.toLocaleLowerCase()),
  );
  return (
    <>
      <div className="page-heading">
        <small>СОХРАНЁННОЕ НА TV</small>
        <h1>
          Медиатека <span className="count">{state.library.length}</span>
        </h1>
        <p>Одна коллекция на всех ваших экранах.</p>
      </div>
      <div className="library-tools">
        <label className="search-input">
          <Icon name="search" />
          <input
            placeholder="Найти в коллекции"
            aria-label="Поиск по медиатеке"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
        </label>
        <Button disabled={disabled} onClick={() => void run("library.sync")}>
          <Icon name="refresh" size={18} />
          <span>{busy ? "…" : "Обновить"}</span>
        </Button>
      </div>
      {items.length ? (
        <div className="film-grid">
          {items.map((item) => (
            <FilmCard
              key={item.hash}
              item={item}
              open={() => actions.openItem(item.hash)}
            />
          ))}
        </div>
      ) : (
        <Empty
          title={
            state.library.length ? "Совпадений нет" : "Медиатека пока пуста"
          }
        >
          {state.library.length
            ? "Попробуйте другое название."
            : "Добавьте первый фильм через поиск."}
        </Empty>
      )}
    </>
  );
}
