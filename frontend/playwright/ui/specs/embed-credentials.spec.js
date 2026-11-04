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
  "/never-reply": parentPage({ replyAfterMs: null }),
};

// The embedding system is served by a real socket on a different port. A page
// fulfilled at an origin with nothing listening behind it is refused by
// Chrome's local network access checks as soon as it frames the app
// (net::ERR_BLOCKED_BY_LOCAL_NETWORK_ACCESS_CHECKS), which has nothing to do
// with the handshake under test. The socket binds on localhost (not
// 127.0.0.1) so the embed stays first-party: cookies planted by the parent
// are same-site with the app and reach its requests, like the same-host
// embed topologies the cookie recovery path exists for.
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

  await new Promise((resolve) => parent.listen(0, "localhost", resolve));
  parentOrigin = `http://localhost:${parent.address().port}`;
});

test.afterAll(async () => {
  await new Promise((resolve) => parent.close(resolve));
});

async function openEmbeddedApp(
  page,
  {
    path,
    parentOrigin: configured,
    timeoutMs = TIMEOUT_MS,
    deadlineMs = 30000,
  },
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

  await page.route(`**/api/main/methods/get-profile`, async (route) => {
    const cookie = (await route.request().headerValue("cookie")) || "";
    const fixture = cookie.includes("auth-token=")
      ? "logged-in-user/get-profile-logged-in.json"
      : "get-profile-anonymous.json";
    await route.fulfill({
      status: 200,
      contentType: "application/transit+json",
      path: `playwright/data/${fixture}`,
    });
  });
  await BasePage.mockConfigFlags(page, [], {
    ...(configured ? { penpotEmbedParentOrigin: configured } : {}),
    penpotEmbedTimeoutMs: timeoutMs,
    penpotEmbedBootDeadlineMs: deadlineMs,
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

  // No cookie is planted in this test, so the cookie-aware get-profile mock
  // answers with the anonymous profile and the post-boot traffic is the
  // anonymous-cascade requests: >= 2 means the get-profile plus that
  // cascade, all carrying the credentials.
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

test("waits for late credentials instead of booting logged out", async ({
  page,
}) => {
  const { backend, logs } = await openEmbeddedApp(page, {
    path: "/ready-late",
    parentOrigin,
  });

  // The gate holds the boot until the credentials land: no logged-out boot
  // traffic (flags etc.) goes out uncredentialed. Probe get-profile calls
  // are exempt — probing the cookie jar is by design uncredentialed and
  // only starts after the timeout.
  await expect
    .poll(() => backend.filter((r) => r.token === TOKEN).length)
    .toBeGreaterThan(0);
  expect(
    backend.filter(
      (r) => !r.token && !r.clientId && !r.path.endsWith("/get-profile"),
    ),
  ).toEqual([]);

  // Only one ready was posted (the gate opened on the 1200ms reply, well
  // before the 2s resend tick), and the reply was used.
  await expect.poll(() => page.evaluate("window.parentLog.replies")).toBe(1);
  expect(logs()).not.toContain("embed: timed out waiting for credentials");
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

test("recovers when the parent plants the cookie but never replies", async ({
  page,
  context,
}) => {
  const { backend, logs } = await openEmbeddedApp(page, {
    path: "/never-reply",
    parentOrigin,
    timeoutMs: 300,
    deadlineMs: 8000,
  });

  // No credentials ever arrive; the parent (simulated via playwright's
  // cookie jar, same effect as document.cookie on a same-origin host)
  // plants a working auth cookie after the timeout-only window.
  // Wait for the first (anonymous) probe, then plant the cookie — the
  // parent acts only after the app has started probing the cookie jar.
  await expect
    .poll(() => backend.filter((r) => r.path.endsWith("/get-profile")).length)
    .toBeGreaterThan(0);
  await context.addCookies([
    { name: "auth-token", value: "planted-by-parent", url: GUEST_URL },
  ]);

  // The probe picks the cookie up and the app boots authenticated: no
  // logged-out boot, no degraded timeout.
  await expect
    .poll(() => backend.filter((r) => r.path.endsWith("/get-profile")).length, {
      timeout: 15000,
    })
    .toBeGreaterThan(1);
  await expect.poll(() => logs()).toContain("embed: session cookie detected");
  expect(logs()).not.toContain("embed: timed out waiting for credentials");
});
