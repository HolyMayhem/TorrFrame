import { copyFileSync, mkdirSync } from "node:fs";
mkdirSync("public", { recursive: true });
copyFileSync(
  "../../icons/TorrFrame FrameElement.png",
  "public/app-icon.png",
);
