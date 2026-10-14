import { copyFileSync, mkdirSync } from "node:fs";
mkdirSync("public", { recursive: true });
copyFileSync(
  "../../icons/round icon.png",
  "public/app-icon.png",
);
