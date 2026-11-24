const byId = id => document.getElementById(id);
const number = new Intl.NumberFormat("ru-RU");
const dates = new Intl.DateTimeFormat("ru-RU",{ timeZone:"UTC", day:"numeric", month:"short" });
const fullDate = new Intl.DateTimeFormat("ru-RU",{ timeZone:"UTC", dateStyle:"medium", timeStyle:"short" });
const countries = new Intl.DisplayNames(["ru"], { type: "region" });
const apiNames = {26:"8.0",27:"8.1",28:"9",29:"10",30:"11",31:"12",32:"12L",33:"13",34:"14",35:"15",36:"16",37:"17"};
const memory = {lt1:"Менее 1 ГБ","1to2":"1–2 ГБ","2to3":"2–3 ГБ","3to4":"3–4 ГБ","4to6":"4–6 ГБ","6to8":"6–8 ГБ",gte8:"8 ГБ и более"};
const names = {
  builtinTorrServer:"Встроенный TorrServer",externalTorrServer:"Внешний TorrServer",
  builtinPlayer:"Встроенный плеер",externalPlayer:"Внешний плеер",phoneRemote:"Phone Remote",
  torrentSearch:"TorrentSearch",jackett:"Jackett",tmdb:"Каталог TMDB",cub:"Каталог CUB",
  afr:"Автоматическая частота экрана",favorites:"Избранное",watchLater:"Смотреть позже",
};
const fmt = value => value == null ? "—" : number.format(value);
const percent = value => value == null ? "—" : fmt(value) + "%";
const day = value => dates.format(new Date(value + "T00:00:00Z"));
const label = (value, convert) => value === "Other" ? "Другие" : value === "Unknown" || value === "XX"
  ? "Неизвестно" : convert?.(value) ?? value;
function node(name, text, className) {
  const element = document.createElement(name);
  if (text != null) element.textContent = text;
  if (className) element.className = className;
  return element;
}
function makeBar(name, count, percentage, sample) {
  const li = node("li");
  const title = node("div", null, "bar-label");
  title.append(node("strong",name),node("span",`${fmt(count)} · ${percent(percentage)}`));
  const progress = node("progress");
  progress.max = 100; progress.value = percentage ?? 0;
  progress.setAttribute("aria-label", name);
  li.append(title, progress);
  if (sample != null) li.append(node("small",`Установок с отчётами · ${fmt(sample)}`,"sample"));
  return li;
}
function distribution(parent, title, rows, convert) {
  const panel = node("article",null,"panel");
  panel.append(node("h3",title));
  if (rows?.length) {
    const list = node("ul",null,"bar-list");
    rows.forEach(item => list.append(makeBar(label(item.value,convert), item.count, item.percent)));
    panel.append(list);
  } else panel.append(node("p","Нет данных","muted"));
  parent.append(panel);
}
let current;
let controller;
function draw() {
  if (!current?.daily?.length) return;
  const canvas = byId("chart");
  const size = canvas.getBoundingClientRect();
  if (size.width < 1 || size.height < 1) return;
  const ratio = window.devicePixelRatio || 1;
  canvas.width = size.width * ratio; canvas.height = size.height * ratio;
  const ctx = canvas.getContext("2d"); ctx.scale(ratio,ratio);
  const rows = current.daily, left = 36, top = 10, width = size.width - left - 12, height = size.height - top - 25;
  const peak = Math.max(1,...rows.map(d => Math.max(d.active,d.new)));
  const countTicks = Math.min(4,peak);
  ctx.font = "11px system-ui"; ctx.fillStyle = "#a7b7a9"; ctx.strokeStyle = "#343e36";
  ctx.textAlign = "right"; ctx.textBaseline = "middle";
  const x = index => left + (rows.length === 1 ? .5 : index/(rows.length-1))*width;
  const y = value => top + height*(1-value/peak);
  for (let index=0; index<=countTicks; index++) {
    const value = Math.round(peak * index/countTicks);
    ctx.beginPath(); ctx.moveTo(left,y(value)); ctx.lineTo(left+width,y(value)); ctx.stroke(); ctx.fillText(fmt(value),left-6,y(value));
  }
  [["active","#87e59b"],["new","#5c9c7e"]].forEach(([key,color]) => {
    ctx.strokeStyle = color; ctx.fillStyle = color; ctx.lineWidth = 2;
    ctx.beginPath();
    rows.forEach((row,index)=> index ? ctx.lineTo(x(index),y(row[key])) : ctx.moveTo(x(index),y(row[key])));
    ctx.stroke();
    if (rows.length === 1) {ctx.beginPath();ctx.arc(x(0),y(rows[0][key]),3,0,Math.PI*2);ctx.fill();}
  });
  ctx.fillStyle = "#a7b7a9"; ctx.textBaseline = "top";
  const ticks = Math.min(5,Math.max(1,Math.floor(size.width/120)),rows.length);
  for (let index=0; index<ticks; index++) {
    const point = ticks === 1 ? 0 : Math.round(index*(rows.length-1)/(ticks-1));
    ctx.textAlign = point===0 ? "left" : point===rows.length-1 ? "right" : "center";
    ctx.fillText(day(rows[point].date),x(point),top+height+6);
  }
}
function render(data) {
  current = data;
  for (const key of ["installations","new","dau","wau","mau"]) byId(key).textContent = fmt(data.totals[key]);
  byId("latest-percent").textContent = percent(data.totals.latestPercent);
  byId("received").textContent = "Последний отчёт · " + (data.lastReceived == null ? "—" : fullDate.format(data.lastReceived) + " UTC") + (data.testEnvironment ? " · Тестовое окружение" : "");
  byId("period").textContent = `${day(data.range.from)} — ${day(data.range.to)} · UTC`;
  byId("device-sample").textContent = "Установок за период · " + fmt(data.totals.sample);
  byId("version-caption").textContent = data.totals.latestBuild == null ? "Актуальный номер сборки не задан"
    : `Сборка ${fmt(data.totals.latestBuild)} · на более ранних сборках ${fmt(data.totals.outdated)} · обновились ${percent(data.totals.upgradePercent)} (выборка ${fmt(data.totals.upgradeSample)})`;
  for (const id of ["device-cards","feature-cards","country-cards","version-cards","appearance-cards","daily-rows"]) byId(id).replaceChildren();
  const d = data.distributions;
  const device = byId("device-cards");
  distribution(device,"Производители",d.manufacturers);
  distribution(device,"Модели",d.models);
  distribution(device,"Версии Android",d.android,v=>apiNames[v] ? "Android " + apiNames[v] : "API " + v);
  distribution(device,"Уровень API",d.android,v=>"API " + v);
  distribution(device,"Архитектуры сборок",d.architectures);
  distribution(device,"Оперативная память",d.memory,v=>memory[v]);
  distribution(device,"Типы устройств",d.deviceTypes,v=>v==="Android TV" ? v : "Другое");
  distribution(byId("country-cards"),"Страны подключений",d.countries,v=>countries.of(v));
  distribution(byId("version-cards"),"Версии приложения",d.versions);
  distribution(byId("version-cards"),"Номера сборок",d.builds);
  distribution(byId("appearance-cards"),"Язык интерфейса",d.languages,v=>v==="ru" ? "Русский" : "English");
  distribution(byId("appearance-cards"),"Тема",d.themes,v=>v==="light" ? "Светлая" : "Тёмная");
  for (const feature of data.features) {
    const list = node("ul",null,"bar-list");
    list.append(makeBar(names[feature.id],feature.count,feature.percent,feature.sample));
    byId("feature-cards").append(list);
  }
  for (const row of data.daily) {
    const tr = node("tr");
    tr.append(node("td",row.date),node("td",fmt(row.active)),node("td",fmt(row.new)));
    byId("daily-rows").append(tr);
  }
  for (const field of ["from","to"]) {
    const element = byId(field);
    element.value = data.range[field]; element.min = data.range.earliest; element.max = data.range.today;
  }
  byId("data").hidden = false;
  draw();
}
function status(message, state = "normal") {
  byId("notice").textContent = message; byId("notice").dataset.state = state; byId("notice").hidden = !message;
}
async function load(params) {
  controller?.abort();
  const request = new AbortController(); controller = request;
  byId("refresh").disabled = true;
  status("Загрузка статистики…");
  try {
    const response = await fetch("/admin/api/stats?" + params, {signal:request.signal});
    if (!response.ok) throw Error(({400:"Проверьте даты выбранного периода.",401:"Требуется авторизация.",429:"Слишком много запросов. Повторите позже."})[response.status] ?? "Статистика временно недоступна.");
    const data = await response.json();
    if (controller !== request) return;
    render(data);
    status(data.totals.sample ? "" : "За выбранный период отчётов нет.");
  } catch (error) {
    if (!request.signal.aborted) status(error.message || "Статистика временно недоступна.","error");
  } finally {
    if (controller === request) byId("refresh").disabled = false;
  }
}
function health() {
  fetch("/health").then(r => r.ok ? "ready" : "error").catch(() => "error").then(state => {
    byId("health").dataset.state = state;
    byId("health").querySelector("span").textContent = state === "ready" ? "Сервер доступен" : "Сервер недоступен";
  });
}
function selectPreset(value) {
  for (const button of document.querySelectorAll("[data-days]")) button.setAttribute("aria-pressed",String(button.dataset.days === value));
}
let selected = "days=30";
for (const button of document.querySelectorAll("[data-days]")) button.addEventListener("click",() => {
  selectPreset(button.dataset.days);
  selected = "days=" + button.dataset.days; load(selected);
});
byId("filters").addEventListener("submit",event => {
  event.preventDefault();
  selectPreset(null);
  selected = new URLSearchParams({from:byId("from").value,to:byId("to").value});
  load(selected);
});
byId("refresh").addEventListener("click",()=>{health();load(selected);});
new ResizeObserver(draw).observe(byId("chart"));
health();load(selected);
