import { copyFileSync, mkdirSync } from "node:fs";
mkdirSync("public", { recursive: true });
copyFileSync(
  "../app/src/main/res/mipmap-xxhdpi/app_icon.png",
  "public/app-icon.png",
);
