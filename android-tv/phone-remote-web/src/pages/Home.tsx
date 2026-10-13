import type { Actions } from "../actions";
import type { Snapshot } from "../types";
import { Button, Empty, FilmCard, Icon } from "../components/Ui";
export function Home({
  state,
  actions,
  navigate,
}: {
  state: Snapshot;
  actions: Actions;
  navigate: (tab: string) => void;
}) {
  const recent = state.library.slice(0, 6);
  return (
    <>
      <section className="hero">
        <div className="eyebrow">ВАШ ДОМАШНИЙ КИНОЗАЛ</div>
        <h1>
          Большой экран.
          <br />
          <span>Управление в руках.</span>
        </h1>
        <p>Найдите следующий фильм и отправьте его на телевизор.</p>
        <Button kind="primary" onClick={() => navigate("search")}>
          <Icon name="search" />
          Найти фильм
        </Button>
        <div className="hero-mark">
          <Icon name="tv" size={96} />
        </div>
      </section>
      <div className="status-strip">
        <span>
          <i
            className={`dot ${state.server.connection === "Connected" ? "live" : ""}`}
          />
          TorrServer{" "}
          {state.server.connection === "Connected" ? "работает" : "недоступен"}
        </span>
        <small>{state.device.name}</small>
      </div>
      {state.playback.active && (
        <button className="now-playing" onClick={() => navigate("remote")}>
          <div className="play-badge">
            <Icon name={state.playback.playing ? "pause" : "play"} />
          </div>
          <div>
            <small>
              {state.playback.buffering ? "БУФЕРИЗАЦИЯ" : "СЕЙЧАС НА TV"}
            </small>
            <strong>{state.playback.title}</strong>
          </div>
          <Icon name="chevron" />
        </button>
      )}
      <div className="section-title">
        <div>
          <small>ВАША КОЛЛЕКЦИЯ</small>
          <h2>Недавно добавленные</h2>
        </div>
        <button className="text-button" onClick={() => navigate("library")}>
          Все <Icon name="chevron" size={16} />
        </button>
      </div>
      {recent.length ? (
        <div className="film-grid">
          {recent.map((item) => (
            <FilmCard
              key={item.hash}
              item={item}
              open={() => actions.openItem(item.hash)}
            />
          ))}
        </div>
      ) : (
        <Empty title="Начните свою коллекцию">
          Найдите фильм через Jackett. Постеры, информация и история просмотра
          появятся здесь.
        </Empty>
      )}
      <div className="quick-grid">
        <button className="quick" onClick={() => navigate("library")}>
          <Icon name="library" />
          <strong>Медиатека</strong>
          <small>Фильмы и история</small>
        </button>
        <button className="quick" onClick={() => navigate("settings")}>
          <Icon name="settings" />
          <strong>Настройки</strong>
          <small>Поиск и TorrServer</small>
        </button>
      </div>
    </>
  );
}
