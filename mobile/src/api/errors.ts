/** One invalid field of a 400 VALIDATION_ERROR (docs/api-contract.md section 1). */
export interface FieldError {
  field: string;
  message: string;
}

/**
 * An RFC 9457 problem returned by the backend, or a network failure (status 0, code NETWORK).
 * `code` is the stable machine-readable error code; UI text comes from the translation file.
 */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    readonly fieldErrors: FieldError[] = [],
    readonly requestId?: string,
  ) {
    super(`API error ${status} ${code}`);
    this.name = 'ApiError';
  }
}

export const NETWORK_ERROR = 'NETWORK';

interface ProblemBody {
  code?: unknown;
  errors?: unknown;
  requestId?: unknown;
}

export async function toApiError(response: Response): Promise<ApiError> {
  let body: ProblemBody = {};
  try {
    body = (await response.json()) as ProblemBody;
  } catch {
    // Not JSON (for example a proxy error page): keep the HTTP status only.
  }
  const code = typeof body.code === 'string' ? body.code : `HTTP_${response.status}`;
  const errors = Array.isArray(body.errors)
    ? body.errors.filter(
        (e): e is FieldError =>
          typeof e === 'object' &&
          e !== null &&
          typeof (e as FieldError).field === 'string' &&
          typeof (e as FieldError).message === 'string',
      )
    : [];
  return new ApiError(
    response.status,
    code,
    errors,
    typeof body.requestId === 'string' ? body.requestId : undefined,
  );
}
