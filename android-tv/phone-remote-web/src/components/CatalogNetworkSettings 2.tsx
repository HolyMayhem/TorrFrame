import type { CatalogNetworkSettings as Network } from "../types";
import { t } from "../i18n";
import { Button, Panel, Toggle } from "./Ui";

export function CatalogNetworkSettings({ value, edit }: {
  value: Network;
  edit: (name: string, value: unknown) => void;
}) {
  const network = value ?? { route: "Direct", source: "Tmdb", apiProxy: "", imageMirrors: [], cubUrl: "", cubTokenConfigured: false, showFavorites: true, showWatchLater: true };
  function change(key: keyof Network, value: unknown) {
    const { cubTokenConfigured: _, ...editable } = network;
    edit("catalogNetwork", { ...editable, [key]: value });
  }
  return <Panel title={t("Каталог", "Catalog")}>
    <label className="field">{t("Источник каталога", "Catalog source")}
      <select value={network.source} onChange={e => change("source", e.target.value)}>
        <option value="Tmdb">TMDB</option>
        <option value="Cub">CUB</option>
        <option value="Auto">{t("Автоматически · TMDB, затем CUB", "Automatic · TMDB, then CUB")}</option>
      </select>
    </label>
    <p className="muted">{t("В автоматическом режиме CUB используется при ошибке TMDB. Подробные карточки остаются на TMDB. CUB может возвращать русские названия.", "Automatic mode uses CUB if TMDB fails. Full details still use TMDB. CUB may return Russian titles.")}</p>
    <Toggle label={t("Показывать избранное в начале каталога", "Show favorites at the top of the catalog")} checked={network.showFavorites ?? true} onChange={value => change("showFavorites", value)} />
    <Toggle label={t("Показывать «Смотреть позже» в начале каталога", "Show Watch later at the top of the catalog")} checked={network.showWatchLater ?? true} onChange={value => change("showWatchLater", value)} />
    <details><summary>{t("Дополнительные настройки подключения", "Advanced connection settings")}</summary>
    <label className="field">{t("Маршрут запросов TMDB", "TMDB request routing")}
      <select value={network.route} onChange={e => change("route", e.target.value)}>
        <option value="Direct">{t("Напрямую", "Direct")}</option>
        <option value="Auto">{t("Автоматически: прямое подключение, затем прокси", "Automatic: direct, then proxy")}</option>
        <option value="Proxy" disabled={!network.apiProxy || !network.imageMirrors.length}>{t("Только прокси и зеркала", "Proxy and mirrors only")}</option>
      </select>
    </label>
    <p className="muted">{t("Укажите HTTPS-адреса сервисов, которыми вам разрешено пользоваться. Для API адрес включает /3/, для постеров — путь перед /t/p/.", "Enter HTTPS endpoints you are authorized to use. The API base includes /3/; image bases precede /t/p/.")}</p>
    <label className="field">{t("Прокси API", "API proxy")}
      <input type="url" placeholder="https://proxy.example/3/" value={network.apiProxy} onChange={e => change("apiProxy", e.target.value.trim())} autoCapitalize="none" autoCorrect="off" spellCheck={false} />
    </label>
    {[0, 1, 2].map(index => <label className="field" key={index}>{t("Зеркало постеров", "Poster mirror")} {index + 1}
      <input type="url" value={network.imageMirrors[index] ?? ""} onChange={e => {
        const mirrors = [...network.imageMirrors];
        while (mirrors.length <= index) mirrors.push("");
        mirrors[index] = e.target.value.trim();
        change("imageMirrors", [...new Set(mirrors.filter(Boolean))]);
      }} autoCapitalize="none" autoCorrect="off" spellCheck={false} />
    </label>)}
    <p className="muted">{t("Публичный tmdb.cub.best работает без токена. Пустой адрес использует этот сервис; другой адрес и токен необязательны.", "The public tmdb.cub.best catalog requires no token. An empty URL uses this service; a custom URL and token are optional.")}</p>
    <label className="field">{t("Адрес сервиса каталога CUB", "CUB catalog service URL")}
      <input type="url" value={network.cubUrl} onChange={e => change("cubUrl", e.target.value.trim())} autoCapitalize="none" autoCorrect="off" spellCheck={false} />
    </label>
    <label className="field">{t("Токен доступа CUB", "CUB access token")}
      <input type="password" autoComplete="new-password" value={network.cubToken ?? ""} placeholder={network.cubTokenConfigured ? t("Токен сохранён", "Token saved") : ""} onChange={e => change("cubToken", e.target.value)} />
    </label>
    {network.cubTokenConfigured && <Button onClick={() => {
      const { cubTokenConfigured: _, ...editable } = network;
      edit("catalogNetwork", { ...editable, source: "Tmdb", cubToken: "" });
    }}>{t("Удалить токен CUB и выбрать TMDB", "Remove CUB token and select TMDB")}</Button>}
    </details>
  </Panel>;
}
