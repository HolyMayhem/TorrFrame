import { createHash } from "node:crypto";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { spawnSync } from "node:child_process";

const DAY = 86_400_000;
const now = Date.now();
const date = ms => new Date(ms).toISOString().slice(0,10);
const rows = [];
const devices = [
  ["Xiaomi","Mi Box S",33,"1to2"],["Google","Chromecast with Google TV",34,"2to3"],
  ["NVIDIA","SHIELD TV Pro",30,"3to4"],["Google","Google TV Streamer",36,"4to6"],
  ["Xiaomi","Mi TV Stick",32,"lt1"],["Sony","Other",35,"2to3"],
];
const countries = ["RU","RU","RU","DE","KZ","US","XX","FR","NL","GB"];
for (let delta = 42; delta >= 0; delta--) {
  const time = now - delta * DAY;
  for (let id = 0; id < 184; id++) {
    const joined = 42 - Math.floor(id/4.3);
    if (delta > joined || delta !== joined && (id * 37 + delta * 13) % 101 > 21 + (41-delta)/4) continue;
    const key = createHash("sha256").update(`local synthetic TorrFrame device ${id}`).digest("hex");
    const [manufacturer, model, api, memory] = devices[id % devices.length];
    const current = delta < 12 && id%5 !== 0 || id%7 === 0;
    let mask = 0;
    [85,26,67,17,45,81,13,65,32,29,54,38].forEach((threshold,bit) => {
      if ((id*13 + bit*41 + delta*7) % 100 < threshold) mask |= 1 << bit;
    });
    rows.push([key,date(time),time,current?"1.0":"0.1.10",current?12:11,api,manufacturer,model,
      id%8===0?"armeabi-v7a":"arm64-v8a",memory,"Android TV",id%4===0?"en":"ru",id%7===0?"light":"dark",
      countries[id%countries.length],delta === joined || id%13===0 ? null : date(time-DAY),mask]);
  }
}
const sql = value => typeof value === "number" ? String(value) : value === null ? "NULL" : "'" + value.replaceAll("'","''") + "'";
const temp = mkdtempSync(join(tmpdir(),"torrframe-demo-"));
try {
  const file = join(temp,"synthetic.sql");
  const statements = [];
  for (let i=0;i<rows.length;i+=100) {
    statements.push(`INSERT OR IGNORE INTO activity(id,day,received_at,version,build,api,manufacturer,model,architecture,
      ram,device,language,theme,country,report_day,report_mask) VALUES ` + rows.slice(i,i+100).map(row => "(" + row.map(sql).join(",") + ")").join(",") + ";");
  }
  writeFileSync(file,statements.join("\n"));
  const persist = process.argv[2];
  const result = spawnSync("./node_modules/.bin/wrangler", ["d1","execute","DB","--local","--config","wrangler.local.jsonc",
    "--file",file,...(persist ? ["--persist-to",persist] : [])],{encoding:"utf8"});
  if (result.status !== 0) throw Error(result.stderr || "Local fixture failed");
  console.log(`Generated ${rows.length} synthetic activity rows for local D1.`);
} finally { rmSync(temp,{recursive:true,force:true}); }
