/**
 * Warms up the freshly started production server: the first request to each route loads its
 * chunks, which can take seconds on a cold server and made the first test run flaky.
 */
export default async function globalSetup() {
  const base = "http://localhost:3100";
  const routes = ["/", "/login", "/dashboard", "/onboarding/connect-ai", "/settings/ai"];
  for (const path of routes) {
    await fetch(base + path, { headers: { cookie: "ANV_SESSION=warmup" }, redirect: "manual" }).catch(() => undefined);
  }
}
