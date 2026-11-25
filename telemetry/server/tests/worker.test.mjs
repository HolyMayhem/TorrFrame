import { after, before, beforeEach, test } from "node:test";
import assert from "node:assert/strict";
import { createHash, randomUUID } from "node:crypto";
import { readFile } from "node:fs/promises";
import { build } from "esbuild";
import { Miniflare, convertV4MiniflareOptions } from "miniflare";

const bundled = await build({ entryPoints: ["src/http.ts"], bundle: true, format: "esm", write: false, platform: "neutral" });
const worker = await import("data:text/javascript;base64," + Buffer.from(bundled.outputFiles[0].contents).toString("base64"));
const DAY = 86400000;
const now = Date.parse("2026-11-25T12:01:00Z");
const dayOf = time => new Date(time).toISOString().slice(0,10);
const secret = "test-identity-secret-".repeat(3);
const password = "test-admin-secret-".repeat(3);
const allow = { limit: async () => ({ success: true }) };
const deny = { limit: async () => ({ success: false }) };
const auth = "Basic " + btoa("admin:" + password);
const features = {
  builtinTorrServer:true, externalTorrServer:false, builtinPlayer:true, externalPlayer:false,
  phoneRemote:false, torrentSearch:true, jackett:false, tmdb:true, cub:false, afr:false, favorites:false, watchLater:false,
};
const fixture = (add = {}) => ({
  schemaVersion:1, installationID:randomUUID(), appVersion:"1.0", appVersionCode:12,
  androidApiLevel:35, manufacturer:"Google", deviceModel:"Google TV Streamer", cpuArchitecture:"arm64-v8a",
  ramBucket:"3to4", deviceType:"Android TV", interfaceLanguage:"ru", theme:"dark", ...add,
});
let emulator, db, env;
before(async () => {
  emulator = new Miniflare(convertV4MiniflareOptions({
    modules:true, script:'export default {fetch(){return new Response("test")}}',
    compatibilityDate:"2026-10-23", d1Databases:{DB:"torrframe-tests"},
  }));
  db = await emulator.getD1Database("DB");
  const migration = await readFile("migrations/0001_telemetry.sql","utf8");
  for (const statement of migration.split(/(?=^(?:CREATE|INSERT INTO totals) )/m).filter(s => s.trim()))
    await db.prepare(statement).run();
});
beforeEach(async () => {
  await db.batch(["DELETE FROM activity","DELETE FROM feature_reports","DELETE FROM daily","DELETE FROM installations",
    "UPDATE totals SET installations=0"].map(sql => db.prepare(sql)));
  env = {
    DB:db, ENVIRONMENT:"development", IDENTITY_HMAC_SECRET:secret, LOCAL_ADMIN_PASSWORD:password, LATEST_BUILD:"12",
    INGRESS:allow, INSTALLATION:allow, ADMIN:allow, ASSETS:{ fetch: async () => new Response("private dashboard") },
  };
});
after(async () => { await emulator?.dispose(); });

function post(payload, time = now, bindings = env, options = {}) {
  const request = new Request("http://localhost/v1/heartbeat", { method:"POST",
    headers: { "Content-Type":"application/json", ...options.headers }, body: JSON.stringify(payload) });
  if (options.country) Object.defineProperty(request,"cf",{value:{country:options.country}});
  return worker.handle(request,bindings,time);
}
function admin(path = "/admin/api/stats", time = now, extra = {}) {
  return worker.handle(new Request("http://localhost" + path,{headers:{Authorization:auth}}),{...env,...extra},time);
}
async function count(table) { return (await db.prepare("SELECT COUNT(*) AS n FROM " + table).first()).n; }

test("validates allowlists, UTC usage dates, nested booleans and exact payload fields", async () => {
  const goodReport = { date:dayOf(now-DAY), features };
  const bad = [
    fixture({title:"content"}), fixture({installationID:"device-serial"}), fixture({appVersion:"build-With-User-ID"}),
    fixture({androidApiLevel:25}), fixture({appVersionCode:"12"}), fixture({manufacturer:"device 1: serial"}),
    fixture({deviceModel:"unique serial"}), fixture({cpuArchitecture:"x86_64"}), fixture({ramBucket:"4GB"}),
    fixture({theme:"automatic"}), fixture({interfaceLanguage:"en-US"}), fixture({schemaVersion:2}),
    fixture({usage:{date:dayOf(now),features}}), fixture({usage:{date:dayOf(now-8*DAY),features}}),
    fixture({usage:{...goodReport, features:{...features,builtinPlayer:1}}}),
    fixture({usage:{...goodReport, features:{...features,query:"term"}}}), fixture({usage:{...goodReport,features:{}}}),
  ];
  for (const value of bad) assert.equal((await post(value)).status,400);
  assert.equal(await count("activity"),0);
  assert.equal((await post(fixture({usage:goodReport}))).status,204);
});
test("rejects non-JSON, invalid UTF-8 and oversized streamed bodies before writing", async () => {
  assert.equal((await post(fixture(), now, env,{headers:{"Content-Type":"text/plain"}})).status,415);
  const payload = new Uint8Array([0xc0,0xaf]);
  assert.equal((await worker.handle(new Request("http://localhost/v1/heartbeat",{
    method:"POST",body:payload,headers:{"Content-Type":"application/json"},
  }),env,now)).status,400);
  assert.equal((await post(fixture({extra:"z".repeat(2500)}))).status,413);
  assert.equal(await count("installations"),0);
});
test("atomic heartbeats deduplicate in parallel, use keyed ids and record only edge country", async () => {
  const entry = fixture();
  const values = await Promise.all(Array.from({length:10},() => post(entry,now,env,
    {country:"RU",headers:{"CF-IPCountry":"GB","User-Agent":"contains-a-custom-string"}})));
  values.forEach(response => {
    assert.equal(response.status,204);
    assert.equal(response.headers.get("X-Telemetry-Day"),dayOf(now));
  });
  const installation = await db.prepare("SELECT * FROM installations").first();
  assert.match(installation.id,/^[a-f0-9]{64}$/);
  assert.notEqual(installation.id,entry.installationID);
  assert.equal((await db.prepare("SELECT country FROM activity").first()).country,"RU");
  assert.equal(await count("activity"),1);
  assert.equal((await db.prepare("SELECT installations FROM totals").first()).installations,1);
  assert.equal((await post(entry,now+DAY)).status,204);
  assert.equal(await count("activity"),2);
  assert.equal((await db.prepare("SELECT country FROM activity ORDER BY day DESC").first()).country,"XX");
});
test("deduplicates feature reports independently and keeps empty reports in the denominator", async () => {
  const usage = { date:dayOf(now-DAY), features };
  const id = randomUUID();
  const first = await post(fixture({installationID:id,usage}));
  assert.equal(first.headers.get("X-Telemetry-Usage"),usage.date);
  assert.equal((await post(fixture({installationID:id,usage}),now+DAY)).status,204);
  const unused = Object.fromEntries(Object.keys(features).map(key => [key,false]));
  await post(fixture({usage:{...usage,features:unused}}));
  await post(fixture());
  assert.equal(await count("feature_reports"),2);
  const data = await (await admin()).json();
  assert.deepEqual(data.features.find(row => row.id === "builtinPlayer"),
    {id:"builtinPlayer",count:1,sample:2,percent:50});
  assert.deepEqual(data.features.find(row => row.id === "phoneRemote"),
    {id:"phoneRemote",count:0,sample:2,percent:0});
  assert.equal(data.totals.sample,3);
});
test("UTC dates, distinct DAU/WAU/MAU and selected version snapshots are correct", async () => {
  const active = randomUUID();
  await post(fixture({installationID:active,appVersionCode:11}),now-4*DAY);
  await post(fixture({installationID:active}),now);
  await post(fixture({appVersionCode:11}),now);
  await post(fixture(),now-17*DAY);
  await post(fixture(),now-40*DAY);
  const data = await (await admin("/admin/api/stats?days=7")).json();
  const {dau,wau,mau,new:recent,current,outdated,latestPercent} = data.totals;
  assert.deepEqual({dau,wau,mau,recent,current,outdated,latestPercent},
    {dau:2,wau:2,mau:3,recent:2,current:1,outdated:1,latestPercent:50});
  assert.equal(data.daily.length,7);
  assert.equal(data.totals.upgradeSample,2);
  assert.equal(data.totals.upgradePercent,50);
  assert.equal(data.distributions.manufacturers[0].value,"Other");
  const old = await (await admin(`/admin/api/stats?from=${dayOf(now-4*DAY)}&to=${dayOf(now-4*DAY)}`)).json();
  assert.equal(old.totals.outdated,1);
  assert.equal(old.totals.dau,1);
  assert.equal((await admin("/admin/api/stats?from=2026-02-30&to=2026-11-25")).status,400);
  assert.equal((await admin("/admin/api/stats?from=2025-11-01&to=2026-11-25")).status,400);
});
test("daily cap is atomic and a previously accepted replay remains successful at capacity", async () => {
  const entry = fixture();
  env.DAILY_LIMIT = "1";
  const results = await Promise.all([post(entry),post(fixture()),post(fixture())]);
  assert.equal(results.filter(r => r.status === 204).length,1);
  assert.equal(await count("activity"),1);
  const accepted = results[0].status === 204 ? entry : fixture();
  if (results[0].status === 204) assert.equal((await post(accepted)).status,204);
  assert.equal((await post(fixture())).status,429);
  assert.equal((await post(fixture(),now+DAY)).status,204);
});
test("global, per-installation and query limits fail before database work", async () => {
  assert.equal((await post(fixture(),now,{...env,INGRESS:deny})).status,429);
  const binding = { limit: async ({key}) => { assert.match(key,/^[a-f0-9]{64}$/); return {success:false}; } };
  assert.equal((await post(fixture(),now,{...env,INSTALLATION:binding})).status,429);
  assert.equal((await admin("/admin/api/stats",now,{ADMIN:deny})).status,429);
  assert.equal(await count("activity"),0);
});
test("scheduled retention prunes identifiable history while preserving permitted daily and all-time totals", async () => {
  await post(fixture(),now-731*DAY);
  await post(fixture(),now-401*DAY);
  await post(fixture({usage:{date:dayOf(now-93*DAY),features}}),now-92*DAY);
  await post(fixture(),now);
  await worker.default.scheduled({scheduledTime:now},env);
  assert.equal(await count("activity"),1);
  assert.equal(await count("feature_reports"),0);
  assert.equal(await count("installations"),2);
  assert.equal(await count("daily"),3);
  assert.equal((await db.prepare("SELECT installations FROM totals").first()).installations,4);
});
test("admin assets, statistics and production fail closed without authorized credentials", async () => {
  for (const path of ["/admin/","/admin/app.js","/admin/styles.css","/admin/api/stats"]) {
    assert.equal((await worker.handle(new Request("http://localhost" + path),env,now)).status,401);
    assert.equal((await admin(path)).status,200);
    assert.equal((await admin(path,now,{ENVIRONMENT:"production"})).status,401);
  }
  assert.equal((await worker.handle(new Request("http://localhost/health"),env,now)).status,200);
  assert.equal((await worker.handle(new Request("http://localhost/index.html"),env,now)).status,404);
  assert.equal((await worker.handle(new Request("http://example.com/admin/",{headers:{Authorization:auth}}),env,now)).status,401);
  assert.equal((await post(fixture(),now,{...env,ENVIRONMENT:"production"})).status,400);
  assert.equal((await post(fixture(),now,{...env,IDENTITY_HMAC_SECRET:""})).status,503);
});

test("production password requires HTTPS and protects each route with an independent credential", async () => {
  const credential = "Basic " + btoa("admin:" + "a".repeat(64));
  const ADMIN_CREDENTIAL_SHA256 = createHash("sha256").update(credential).digest("hex");
  const production = { ...env, ENVIRONMENT:"production", ADMIN_AUTH_MODE:"password", ADMIN_CREDENTIAL_SHA256 };
  const request = (path, authorization, protocol = "https") => new Request(`${protocol}://analytics.test${path}`,
    { headers: authorization ? { Authorization:authorization } : {} });
  for (const path of ["/admin", "/admin/", "/admin/index.html", "/admin/app.js", "/admin/styles.css", "/admin/brand.png", "/admin/api/stats"]) {
    const missing = await worker.handle(request(path), production, now);
    assert.equal(missing.status, 401);
    assert.equal(missing.headers.get("WWW-Authenticate"), 'Basic realm="TorrFrame Analytics"');
    assert.equal(missing.headers.get("Cache-Control"), "no-store");
    assert.equal((await worker.handle(request(path, auth), production, now)).status, 401);
    const verified = await worker.handle(request(path, credential), production, now);
    assert.equal(verified.status, 200);
    assert.equal(verified.headers.get("Cache-Control"), "no-store");
    assert.equal((await worker.handle(request(path, credential), { ...production, ADMIN_CREDENTIAL_SHA256:undefined }, now)).status, 401);
  }
  const plaintext = await worker.handle(request("/admin/", credential, "http"), production, now);
  assert.equal(plaintext.status, 401);
  assert.equal(plaintext.headers.get("WWW-Authenticate"), null);
  assert.equal((await worker.handle(request("/admin/", auth), { ...production, ADMIN:deny }, now)).status, 429);
  assert.equal((await worker.handle(request("/admin/", credential), { ...production, ADMIN:deny }, now)).status, 200);
  assert.equal((await worker.handle(request("/admin/api/stats", credential), { ...production, ADMIN:deny }, now)).status, 429);
});

test("does not expose the Hub RPC methods on the original HTTP handler", async () => {
  for (const path of ["/HubAnalytics/snapshot", "/admin/api/snapshot", "/admin/api/HubAnalytics/health"]) {
    const response = await admin(path);
    assert.equal(response.status, 404);
    assert.doesNotMatch(await response.text(), /installations/);
  }
});
