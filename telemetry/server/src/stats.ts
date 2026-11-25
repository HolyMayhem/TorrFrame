import { DAY, dayOf, dayTime, FEATURES, HISTORY_DAYS, HttpError } from "./contract";

export function rangeFor(url: URL, now: number) {
  const today = dayTime(dayOf(now));
  const earliest = today - (HISTORY_DAYS - 1) * DAY;
  const from = url.searchParams.get("from");
  const to = url.searchParams.get("to");
  const requestedDays = url.searchParams.get("days") ?? "30";
  if ((from === null) !== (to === null) || (!from && !["7","30","90"].includes(requestedDays)))
    throw new HttpError(400, "Invalid period");
  const start = from === null ? today - (Number(requestedDays) - 1) * DAY : dayTime(from);
  const end = to === null ? today : dayTime(to);
  if (!Number.isFinite(start) || !Number.isFinite(end) || start < earliest || start > end || end > today)
    throw new HttpError(400, "Period must be inside the last 90 UTC days");
  return { from: dayOf(start), to: dayOf(end), earliest: dayOf(earliest), today: dayOf(today), start, end };
}
export const percent = (value: number, count: number) => count > 0 ? Math.round(1000 * value / count) / 10 : null;
type Row = { dimension: string; value: string; count: number };
function grouped(rows: Row[], sample: number) {
  const data: Record<string, { value: string; count: number; percent: number | null }[]> = {};
  for (const row of rows) {
    const list = data[row.dimension] ??= [];
    const generic = row.value === "XX" || row.value === "Unknown";
    const label = row.count < 5 && !generic ? "Other" : row.value;
    const item = list.find(value => value.value === label);
    if (item) item.count += row.count;
    else list.push({ value: label, count: row.count, percent: null });
  }
  for (const values of Object.values(data)) {
    values.sort((a, b) => b.count - a.count || a.value.localeCompare(b.value));
    for (const row of values) row.percent = percent(row.count, sample);
  }
  return data;
}

export async function statistics(db: D1Database, url: URL, now: number, latest: string | undefined, hubSection?: string) {
  const range = rangeFor(url, now);
  const latestBuild = /^\d{1,10}$/.test(latest ?? "") && Number(latest) > 0 && Number(latest) <= 2_100_000_000 ? Number(latest) : null;
  const columns = {
    manufacturers: "manufacturer", models: "model", android: "api", architectures: "architecture",
    memory: "ram", deviceTypes: "device", languages: "language", themes: "theme",
    countries: "country", versions: "version", builds: "build",
  };
  const selected = (
    hubSection === undefined || hubSection === "overview" ? Object.keys(columns) :
    hubSection === "devices" ? Object.keys(columns).slice(0, 8) :
    hubSection === "geography" ? ["countries"] :
    hubSection === "versions" ? ["versions", "builds"] : []
  ) as (keyof typeof columns)[];
  const withFeatures = hubSection === undefined || hubSection === "features";
  const comparisonDays = Math.max(0, 1 + (range.end - range.start) / DAY - Number(range.to === range.today));
  const previousStart = dayOf(range.start - comparisonDays * DAY);
  const compare = ["summary", "overview", "audience"].includes(hubSection ?? "") &&
    comparisonDays > 0 && previousStart >= range.earliest;
  const dimensionSql = `WITH latest AS (
    SELECT *, ROW_NUMBER() OVER(PARTITION BY id ORDER BY day DESC) AS row_number
    FROM activity WHERE day BETWEEN ? AND ?
  )
  SELECT labels.key AS dimension, CAST(labels.value AS TEXT) AS value, COUNT(*) AS count
  FROM latest, json_each(json_object(${selected.map(key => `'${key}',${columns[key]}`).join(",")})) AS labels
  WHERE row_number=1 GROUP BY labels.key, labels.value`;
  const featureBits = FEATURES.map((_, index) => `MAX((mask & ${1 << index}) != 0) AS f${index}`).join(",");
  const featureSums = FEATURES.map((_, index) => `COALESCE(SUM(f${index}),0) AS f${index}`).join(",");
  const day7 = dayOf(range.end - 6 * DAY);
  const day30 = dayOf(range.end - 29 * DAY);
  const [total, last, days, active, dimensions, feature] = await db.batch<Record<string, string | number | null>>([
    db.prepare("SELECT installations FROM totals WHERE singleton=1"),
    db.prepare("SELECT last_received FROM daily ORDER BY day DESC LIMIT 1"),
    db.prepare("SELECT day,active,new_installs FROM daily WHERE day BETWEEN ? AND ? ORDER BY day").bind(compare ? previousStart : range.from, range.to),
    db.prepare(`WITH per_installation AS (
      SELECT id, MAX(CASE WHEN day BETWEEN ? AND ? THEN day END) AS chosen_day,
        MAX(CASE WHEN day BETWEEN ? AND ? AND build < ? THEN 1 ELSE 0 END) AS saw_old,
        MAX(day) AS last_day FROM activity WHERE day BETWEEN ? AND ? GROUP BY id
    )
    SELECT COUNT(a.id) AS sample, COUNT(CASE WHEN a.build >= ? THEN 1 END) AS current,
      COALESCE(SUM(t.saw_old),0) AS upgrade_sample,
      COUNT(CASE WHEN t.saw_old = 1 AND a.build >= ? THEN 1 END) AS upgraded,
      COUNT(CASE WHEN t.last_day >= ? THEN 1 END) AS wau, COUNT(CASE WHEN t.last_day >= ? THEN 1 END) AS mau
    FROM per_installation t LEFT JOIN activity a ON a.day=t.chosen_day AND a.id=t.id`)
      .bind(range.from,range.to,range.from,range.to,latestBuild,
        dayOf(Math.min(range.start, Math.max(dayTime(range.earliest), dayTime(day30)))),range.to,latestBuild,latestBuild,day7,day30),
    ...(selected.length ? [db.prepare(dimensionSql).bind(range.from,range.to)] : []),
    ...(withFeatures ? [db.prepare(`SELECT COUNT(*) AS sample, ${featureSums}
      FROM (SELECT ${featureBits} FROM feature_reports WHERE day BETWEEN ? AND ? GROUP BY id)`).bind(range.from,range.to)] : []),
  ]);
  const summary = active.results[0] ?? Object.create(null) as Record<string, string | number | null>;
  const sample = Number(summary.sample ?? 0);
  const featureRow = (selected.length ? feature : dimensions)?.results[0] ?? Object.create(null) as Record<string, string | number | null>;
  const featureSample = Number(featureRow.sample ?? 0);
  const byDay = new Map(days.results.map(d => [d.day, d]));
  const daily = [];
  for (let time = range.start; time <= range.end; time += DAY) {
    const date = dayOf(time);
    const row = byDay.get(date);
    daily.push({ date, active: Number(row?.active ?? 0), new: Number(row?.new_installs ?? 0) });
  }
  const previous = [];
  if (compare) for (let time = dayTime(previousStart); time < range.start; time += DAY) {
    const date = dayOf(time);
    const row = byDay.get(date);
    previous.push({ date, active: Number(row?.active ?? 0), new: Number(row?.new_installs ?? 0) });
  }
  const current = latestBuild === null ? null : Number(summary.current ?? 0);
  const upgradeSample = Number(summary.upgrade_sample ?? 0);
  return {
    range: { from: range.from, to: range.to, earliest: range.earliest, today: range.today },
    totals: { installations: Number(total.results[0]?.installations ?? 0), new: daily.reduce((sum,d) => sum + d.new,0),
      dau: daily.at(-1)?.active ?? 0, wau: day7 < range.earliest ? null : Number(summary.wau ?? 0),
      mau: day30 < range.earliest ? null : Number(summary.mau ?? 0), sample,
      latestBuild, current, outdated: current === null ? null : sample - current,
      latestPercent: current === null ? null : percent(current,sample),
      upgradeSample, upgradePercent: percent(Number(summary.upgraded ?? 0), upgradeSample) },
    lastReceived: last.results[0]?.last_received ?? null,
    daily, distributions: grouped(selected.length ? dimensions.results as unknown as Row[] : [], sample),
    ...(hubSection === undefined ? {} : { previous }),
    features: (withFeatures ? FEATURES : []).map((id, index) => {
      const count = Number(featureRow[`f${index}`] ?? 0);
      return { id, count, sample: featureSample, percent: percent(count,featureSample) };
    }),
  };
}
