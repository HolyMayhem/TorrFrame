import { copyFileSync, mkdirSync } from "node:fs";
mkdirSync("public", { recursive: true });
copyFileSync(
  "../../icons/TorrFrame square icon ver2.png",
  "public/app-icon.png",
);
