import express from "express";
import compression from "compression";
import proxy from "express-http-proxy";
import { fileURLToPath } from "url";
import path from "path";
import fs from "fs";
import { WebSocketServer } from "ws";
import WebSocket from "ws";

const __dirname = fileURLToPath(new URL(".", import.meta.url));

const app = express();
const PORT = process.env.PROXY_PORT || 3001;
const TARGET_URL = "https://design.penpot.app";
const BASE_PATH = process.env.PENPOT_BASE_PATH || "/penpot/";
const staticPath = path.resolve(__dirname, "../resources/public");

const indexPath = path.resolve(staticPath, "index.html");
const getIndexHtml = () => {
  let html = fs.readFileSync(indexPath, "utf-8");
  // Rewrite version tag to "develop" so it matches the compiled JS
  // (shadow-cljs defaults to "develop" unless VERSION_TAG env var is set).
  // Without this, the stale-build check triggers a hard reload every 30s.
  html = html.replace(
    /globalThis\.penpotVersionTag\s*=\s*"[^"]*"/,
    'globalThis.penpotVersionTag = "develop"'
  );
  return html;
};

app.use((req, res, next) => {
  res.header("Access-Control-Allow-Origin", "*");
  res.header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
  res.header("Access-Control-Allow-Headers", "Origin, X-Requested-With, Content-Type, Accept, Authorization");
  if (req.method === "OPTIONS") {
    return res.sendStatus(200);
  }
  next();
});

app.use(compression());

// Proxy API requests to target backend
app.use("/api", proxy(TARGET_URL, {
  proxyReqPathResolver: (req) => {
    return req.originalUrl;
  },
}));

// Proxy RPC requests to target backend
app.use("/rpc", proxy(TARGET_URL, {
  proxyReqPathResolver: (req) => {
    return req.originalUrl;
  },
}));

// Proxy API/RPC requests under base path (before SPA fallback)
app.use(BASE_PATH + "api", proxy(TARGET_URL, {
  proxyReqPathResolver: (req) => "/api" + req.url,
}));

app.use(BASE_PATH + "rpc", proxy(TARGET_URL, {
  proxyReqPathResolver: (req) => "/rpc" + req.url,
}));

// Storage objects (component thumbnails, file media, fonts). Must be
// registered before the SPA fallback: object ids have no file extension,
// so they would otherwise receive index.html.
app.use("/assets", proxy(TARGET_URL, {
  proxyReqPathResolver: (req) => req.originalUrl,
}));

app.use(BASE_PATH + "assets", proxy(TARGET_URL, {
  proxyReqPathResolver: (req) => "/assets" + req.url,
}));

// Serve static files under base path
// Disable caching for local development
app.use((req, res, next) => {
  res.header("Cache-Control", "no-store, no-cache, must-revalidate, proxy-revalidate");
  res.header("Pragma", "no-cache");
  res.header("Expires", "0");
  next();
});

app.use(BASE_PATH, (req, res, next) => {
  // For SPA routes under the prefix (not matching a static file), serve index.html
  const urlPath = req.path;
  const ext = path.extname(urlPath);

  if (!ext || urlPath === "/") {
    // No file extension or root path: serve index.html for SPA routing
    return res.send(getIndexHtml());
  }

  next();
});

app.use(BASE_PATH, express.static(staticPath, {
  etag: false,
  lastModified: false,
  setHeaders: (res) => {
    res.header("Cache-Control", "no-store, no-cache, must-revalidate, proxy-revalidate");
    res.header("Pragma", "no-cache");
    res.header("Expires", "0");
  }
}));

// Root-level static serving (fallback for JS-constructed absolute URLs:
// workers, fonts, media, etc. that use u/join cf/public-uri "path")
app.use(express.static(staticPath, {
  index: false, // Don't serve index.html at root
  etag: false,
  lastModified: false,
  setHeaders: (res) => {
    res.header("Cache-Control", "no-store, no-cache, must-revalidate, proxy-revalidate");
    res.header("Pragma", "no-cache");
    res.header("Expires", "0");
  }
}));

// Redirect root to base path
app.get("/", (req, res) => {
  res.redirect(BASE_PATH);
});

// WebSocket proxy support
const server = app.listen(PORT, () => {
  console.log(`Proxy server listening at http://0.0.0.0:${PORT}`);
  console.log(`Base path: ${BASE_PATH}`);
  console.log(`Static files served from: ${staticPath}`);
  console.log(`Proxied to backend: ${TARGET_URL}`);
});

const wss = new WebSocketServer({ noServer: true });

wss.on("connection", (ws, request) => {
  const targetUrl = new URL(request.url, TARGET_URL);
  targetUrl.protocol = targetUrl.protocol === "https:" ? "wss:" : "ws:";

  const headers = { ...request.headers };
  delete headers["host"];

  const client = new WebSocket(targetUrl.href, {
    headers,
  });

  client.on("message", (data) => {
    if (ws.readyState === 1) {
      ws.send(data);
    }
  });

  ws.on("message", (data) => {
    if (client.readyState === 1) {
      client.send(data);
    }
  });

  client.on("close", () => ws.close());
  ws.on("close", () => client.close());

  client.on("error", (err) => {
    console.error("WebSocket client error:", err.message);
  });

  ws.on("error", (err) => {
    console.error("WebSocket server error:", err.message);
  });
});

server.on("upgrade", (request, socket, head) => {
  const pathname = new URL(request.url, `http://${request.headers.host}`).pathname;

  if (pathname.startsWith("/ws") || pathname.startsWith(BASE_PATH + "ws")) {
    wss.handleUpgrade(request, socket, head, (ws) => {
      wss.emit("connection", ws, request);
    });
  } else {
    socket.destroy();
  }
});
