import type { components } from "./schema";

export type Problem = components["schemas"]["Problem"];

/** An RFC 9457 Problem from the API (TECHSPEC §8.4), thrown by the query hooks. */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly problem: Problem;

  constructor(status: number, problem: Problem | undefined) {
    const p = problem ?? {};
    super(p.detail ?? p.title ?? `Request failed (${status})`);
    this.name = "ApiError";
    this.status = status;
    this.code = p.code ?? "UNKNOWN";
    this.problem = p;
  }
}

/** Turns an openapi-fetch result into data or a thrown {@link ApiError}. */
export function unwrap<T>(result: { data?: T; error?: unknown; response: Response }): T {
  if (result.error !== undefined || !result.response.ok) {
    throw new ApiError(result.response.status, result.error as Problem | undefined);
  }
  return result.data as T;
}
