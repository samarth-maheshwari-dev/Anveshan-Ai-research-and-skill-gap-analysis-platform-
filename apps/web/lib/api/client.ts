import createClient, { type Middleware } from "openapi-fetch";

import { API_URL } from "@/lib/env";
import type { paths } from "./schema";

const SAFE_METHODS = new Set(["GET", "HEAD", "OPTIONS"]);

export function readCookie(name: string, cookieString: string = document.cookie): string | undefined {
  for (const part of cookieString.split(";")) {
    const [k, ...v] = part.trim().split("=");
    if (k === name) return v.join("=");
  }
  return undefined;
}

/** Sends the XSRF-TOKEN cookie back as X-XSRF-TOKEN on writes only (TECHSPEC §5.1). */
export function csrfMiddleware(getCookie: (name: string) => string | undefined = readCookie): Middleware {
  return {
    onRequest({ request }) {
      if (!SAFE_METHODS.has(request.method.toUpperCase())) {
        const token = getCookie("XSRF-TOKEN");
        if (token) request.headers.set("X-XSRF-TOKEN", decodeURIComponent(token));
      }
      return request;
    },
  };
}

/** Any 401 → back to login with "session expired" (APPFLOW §2.2 banner #1), keeping the page to return to. */
export function unauthorizedMiddleware(
  navigate: (url: string) => void = (url) => window.location.assign(url),
  currentPath: () => string = () => window.location.pathname,
): Middleware {
  return {
    onResponse({ response }) {
      const path = currentPath();
      if (response.status === 401 && !path.startsWith("/login")) {
        navigate(`/login?expired=1&next=${encodeURIComponent(path)}`);
      }
      return response;
    },
  };
}

export const api = createClient<paths>({ baseUrl: API_URL, credentials: "include" });
api.use(csrfMiddleware(), unauthorizedMiddleware());
