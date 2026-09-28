import express from "express";
import compression from "compression";

import { fileURLToPath } from "url";
import path from "path";

// Kept in sync with scripts/_helpers.js by hand, as scripts/proxy-server.js
// does: importing that module would drag the whole build toolchain into a
// static file server.
const BASE_PATH = process.env.PENPOT_BASE_PATH || "/penpot/";

const app = express();
const port = 3000;

app.use(compression());

const staticPath = path.join(
  fileURLToPath(import.meta.url),
  "../../resources/public",
);

// The app is built for BASE_PATH (which is picked up from PENPOT_BASE_PATH and
// defaults to "/penpot/" in this fork); index.html carries a matching
// `<base href>`, so it must be served from there. Serving it at the root would
// give a page whose assets all resolve under the base path, where nothing
// answers them -- a blank screen.
const basePath = BASE_PATH;

if (basePath !== "/") {
  // Compared with === rather than expressed as a route: express ignores a
  // trailing slash when matching, so a `app.get("/penpot")` handler would also
  // match "/penpot/" and redirect it to itself forever.
  const bare = basePath.replace(/\/$/, "");
  app.use((req, res, next) => {
    if (req.path === "/" || req.path === bare) return res.redirect(basePath);
    next();
  });
}

// Routes and object ids have no file extension, so without this fallback they
// would be answered with a 404 instead of the app. Mirrors what nginx does in
// production.
app.use(basePath, (req, res, next) => {
  if (path.extname(req.path)) return next();
  res.sendFile(path.join(staticPath, "index.html"));
});

app.use(basePath, express.static(staticPath));

// Keep the historical root mount for requests that are root absolute despite
// the base path; `index: false` so it can never shadow the redirect above.
app.use(express.static(staticPath, { index: false }));

app.listen(port, () => {
  console.log(`Listening at 0.0.0.0:${port}${basePath}`);
});
