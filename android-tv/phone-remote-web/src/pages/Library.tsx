import { t } from "../i18n";
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
  const [magnet, setMagnet] = useState("");
  const { run, disabled, busy } = useAction(actions);
  const items = state.library.filter((item) =>
    item.title.toLocaleLowerCase().includes(query.toLocaleLowerCase()),
  );
  return (
    <>
      <div className="page-heading">
        <small>{t("СОХРАНЁННОЕ НА TV")} </small>
        <h1>
          {t("Медиатека")} <span className="count">{state.library.length}</span>
        </h1>
        <p>{t("Одна коллекция на всех ваших экранах.")} </p>
      </div>
      <div className="library-tools">
        <label className="search-input">
          <Icon name="search" />
          <input
            placeholder={t("Найти в коллекции")}
            aria-label={t("Поиск по медиатеке")}
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
        </label>
        <Button disabled={disabled} onClick={() => void run("library.sync")}>
          <Icon name="refresh" size={18} />
          <span>{busy ? "…" : t("Обновить")}</span>
        </Button>
        <Button kind="danger" disabled={disabled || !state.library.length} onClick={() => void actions.confirm(
          t("Очистить медиатеку?", "Clear the library?"),
          t("Все раздачи на TorrServe и их история просмотра будут удалены. Избранное и «Смотреть позже» сохранятся.", "All torrents on TorrServe and their playback history will be removed. Favorites and Watch later will be kept.")
        ).then(ok => { if (ok) return run("library.clear", { confirmed: true }); })}>
          <Icon name="trash" size={18} />{t("Очистить", "Clear")}
        </Button>
      </div>
      <form
        className="search-form"
        onSubmit={(e) => {
          e.preventDefault();
          void run<{ hash: string }>("library.magnet", {
            magnet: magnet.trim(),
          }).then((result) => {
            if (result) {
              setMagnet("");
              actions.openItem(result.hash);
            }
          });
        }}
      >
        <label className="field">
          {t("Magnet-ссылка", "Magnet link")}
          <input
            aria-label={t("Magnet-ссылка", "Magnet link")}
            value={magnet}
            maxLength={8192}
            placeholder="magnet:?xt=urn:btih:…"
            autoCapitalize="none"
            autoCorrect="off"
            onChange={(e) => setMagnet(e.target.value)}
          />
        </label>
        <Button
          type="submit"
          disabled={disabled || !/^magnet:\?/i.test(magnet.trim())}
        >
          {t("Добавить", "Add")}
        </Button>
      </form>
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
            state.library.length
              ? t("Совпадений нет")
              : t("Медиатека пока пуста")
          }
        >
          {state.library.length
            ? t("Попробуйте другое название.")
            : t("Добавьте первый фильм через поиск.")}
        </Empty>
      )}
    </>
  );
}
