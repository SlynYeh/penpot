import { createServer } from "node:http";

import { test, expect } from "@playwright/test";

import { BasePage } from "../pages/BasePage";
import config from "../../../playwright.config";

// Iframe credentials handshake: the app posts a `ready` message to the system
// embedding it and that system replies with the X-token / X-ClientId pair,
// which the app then sends as request headers on every backend call
// (app.util.embed, app.util.http/default-headers).
const APP_BASE_PATH = process.env.PENPOT_BASE_PATH || "/penpot/";
const APP_ORIGIN = config.use.baseURL;
const GUEST_ORIGIN = new URL(APP_BASE_PATH, APP_ORIGIN).origin;
const GUEST_URL = new URL(APP_BASE_PATH, APP_ORIGIN).href;

const TOKEN = "embed-token-9f3a2b";
const CLIENT_ID = "embed-client-4c71d8";

// Long enough for the app to give up waiting (see `timeoutMs` below), short
// enough that the late reply still lands while the test is polling.
const LATE_REPLY_MS = 1200;
const TIMEOUT_MS = 300;

const parentPage = ({ replyAfterMs = null, postUnsolicited = false }) =>
  `<!doctype html>
<html lang="en">
  <head><meta charset="utf-8" /><title>third party system</title></head>
  <body>
    <iframe id="penpot" src="${GUEST_URL}" style="width:1024px;height:768px"></iframe>
    <script>
      const frame = document.getElementById("penpot");
      const guestOrigin = ${JSON.stringify(GUEST_ORIGIN)};
      const credentials = ${JSON.stringify({ "X-token": TOKEN, "X-ClientId": CLIENT_ID })};
      window.parentLog = { received: [], replies: 0, unsolicited: 0 };

      window.addEventListener("message", (event) => {
        window.parentLog.received.push({ origin: event.origin, data: event.data });
        if (event.origin !== guestOrigin) return;
        if (!event.data || event.data.scope !== "penpot/embed" || event.data.type !== "ready") return;
        ${
          replyAfterMs === null
            ? "// this parent never answers the ready message"
            : `setTimeout(() => {
          frame.contentWindow.postMessage(credentials, guestOrigin);
          window.parentLog.replies++;
        }, ${replyAfterMs});`
        }
      });

      ${
        postUnsolicited
          ? `frame.addEventListener("load", () => setTimeout(() => {
          frame.contentWindow.postMessage(credentials, guestOrigin);
          window.parentLog.unsolicited++;
        }, 700));`
          : ""
      }
    </script>
  </body>
</html>`;

const PARENT_PAGES = {
  "/ready": parentPage({ replyAfterMs: 0 }),
  "/ready-late": parentPage({ replyAfterMs: LATE_REPLY_MS }),
  "/unsolicited": parentPage({ postUnsolicited: true }),
};

// The embedding system is served by a real socket on a different port. A page
// fulfilled at an origin with nothing listening behind it is refused by
// Chrome's local network access checks as soon as it frames the app
// (net::ERR_BLOCKED_BY_LOCAL_NETWORK_ACCESS_CHECKS), which has nothing to do
// with the handshake under test.
let parent = null;
let parentOrigin = null;

test.beforeAll(async () => {
  parent = createServer((req, res) => {
    const body = PARENT_PAGES[new URL(req.url, "http://localhost").pathname];
    if (!body) {
      res.writeHead(404).end();
      return;
    }
    res.writeHead(200, { "content-type": "text/html; charset=utf-8" });
    res.end(body);
  });

  await new Promise((resolve) => parent.listen(0, "127.0.0.1", resolve));
  parentOrigin = `http://127.0.0.1:${parent.address().port}`;
});

test.afterAll(async () => {
  await new Promise((resolve) => parent.close(resolve));
});

async function openEmbeddedApp(
  page,
  { path, parentOrigin: configured, timeoutMs = TIMEOUT_MS },
) {
  const console = [];
  const backend = [];

  page.on("console", (message) => console.push(message.text()));
  page.on("request", (request) => {
    const { pathname } = new URL(request.url());
    if (!pathname.includes("/api/")) return;
    backend.push({
      path: pathname,
      token: request.headers()["x-token"],
      clientId: request.headers()["x-clientid"],
    });
  });

  await BasePage.mockRPC(
    page,
    "get-profile",
    "logged-in-user/get-profile-logged-in.json",
  );
  await BasePage.mockConfigFlags(page, [], {
    ...(configured ? { penpotEmbedParentOrigin: configured } : {}),
    penpotEmbedTimeoutMs: timeoutMs,
  });

  await page.goto(`${parentOrigin}${path}`);

  return { console, backend, logs: () => console.join("\n") };
}

test("sends the ready message to the parent and attaches the credentials to every backend call", async ({
  page,
}) => {
  const { backend, logs } = await openEmbeddedApp(page, {
    path: "/ready",
    parentOrigin,
  });

  await expect.poll(() => page.evaluate("window.parentLog.replies")).toBe(1);
  expect(await page.evaluate("window.parentLog.received")).toEqual([
    { origin: GUEST_ORIGIN, data: { scope: "penpot/embed", type: "ready" } },
  ]);

  await expect.poll(() => backend.length).toBeGreaterThanOrEqual(2);
  expect(backend.filter((r) => !r.token || !r.clientId)).toEqual([]);
  expect(backend).toContainEqual({
    path: `${APP_BASE_PATH}api/main/methods/get-profile`,
    token: TOKEN,
    clientId: CLIENT_ID,
  });

  expect(logs()).toContain("embed: credentials received");
  // The credentials are logged by length only; the values must never reach the
  // console.
  expect(logs()).not.toContain(TOKEN);
  expect(logs()).not.toContain(CLIENT_ID);
});

test("boots without credentials when the parent replies late, then refreshes with them", async ({
  page,
}) => {
  const { backend, logs } = await openEmbeddedApp(page, {
    path: "/ready-late",
    parentOrigin,
  });

  await expect
    .poll(() => backend.filter((r) => !r.token).length)
    .toBeGreaterThan(0);
  expect(logs()).toContain("embed: timed out waiting for credentials");

  await expect
    .poll(() => backend.filter((r) => r.token === TOKEN).length)
    .toBeGreaterThan(0);
  expect(logs()).toContain(
    "embed: credentials arrived late, refreshing profile",
  );

  // The refresh is what makes the late credentials useful: a second profile
  // fetch, this time authenticated.
  const profiles = backend.filter((r) => r.path.endsWith("/get-profile"));
  expect(profiles.length).toBeGreaterThanOrEqual(2);
  expect(profiles[0]).toMatchObject({ token: undefined, clientId: undefined });
  expect(profiles.at(-1)).toMatchObject({ token: TOKEN, clientId: CLIENT_ID });
});

test("rejects credentials posted by a window that is not the configured parent origin", async ({
  page,
}) => {
  const { backend, logs } = await openEmbeddedApp(page, {
    path: "/unsolicited",
    parentOrigin: "http://localhost:9998",
  });

  // The ready message is addressed to the configured origin, so this parent
  // never receives it...
  await expect
    .poll(() => page.evaluate("window.parentLog.unsolicited"))
    .toBeGreaterThan(0);
  expect(await page.evaluate("window.parentLog.received")).toEqual([]);

  // ...and the credentials it posts anyway are dropped by the origin check, so
  // the app keeps calling the backend unauthenticated.
  await expect.poll(() => backend.length).toBeGreaterThan(0);
  expect(logs()).not.toContain("embed: credentials received");
  expect(backend.filter((r) => r.token || r.clientId)).toEqual([]);
});

test("accepts credentials from any window when no parent origin is configured", async ({
  page,
}) => {
  const { backend } = await openEmbeddedApp(page, { path: "/ready" });

  await expect.poll(() => page.evaluate("window.parentLog.replies")).toBe(1);
  await expect.poll(() => backend.length).toBeGreaterThan(0);
  expect(backend.filter((r) => !r.token || !r.clientId)).toEqual([]);
});
