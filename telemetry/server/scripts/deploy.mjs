import { readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";

const config = "wrangler.production.json";
const v = JSON.parse(readFileSync(config, "utf8"));
const d1 = v.d1_databases?.[0];
const hosts = v.routes?.map(route => route.pattern) ?? [];
if (v.name !== "torrframe-telemetry" || v.vars?.ENVIRONMENT !== "production" ||
    v.d1_databases.length !== 1 || d1?.binding !== "DB" || d1.database_name !== "torrframe-analytics" ||
    !/^[a-f0-9-]{36}$/.test(d1.database_id) ||
    !hosts.length || hosts.some(host => !/^[a-z0-9.-]+$/.test(host) || host.endsWith("example.com")) ||
    !/^[a-f0-9]{64}$/.test(v.vars.ACCESS_AUDIENCE) ||
    !/^https:\/\/[a-z0-9-]+\.cloudflareaccess\.com$/.test(v.vars.ACCESS_ISSUER) ||
    v.workers_dev !== false || v.preview_urls !== false || v.observability?.enabled !== false ||
    v.assets?.run_worker_first !== true) throw Error("Check the standalone production configuration.");

const command = process.argv[2];
const args = command === "migrate" ? ["d1","migrations","apply","DB","--remote"] :
    command === "publish" ? ["deploy"] : command === "dry-run" ? ["deploy","--dry-run"] : null;
if (!args) throw Error("Use npm run deploy -- migrate, dry-run or publish.");
if (command !== "dry-run") {
  const inventory = spawnSync("./node_modules/.bin/wrangler", ["d1","list","--json","--config",config], {encoding:"utf8"});
  if (inventory.status !== 0 || !JSON.parse(inventory.stdout).some(item => item.uuid === d1.database_id && item.name === "torrframe-analytics"))
    throw Error("The configured D1 ID does not belong to torrframe-analytics in this account.");
}
const result = spawnSync("./node_modules/.bin/wrangler", [...args,"--config",config], { stdio: "inherit" });
process.exit(result.status ?? 1);
