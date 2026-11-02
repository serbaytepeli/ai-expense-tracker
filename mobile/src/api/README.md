# api

`schema.ts` is **generated** from the backend OpenAPI document; do not edit it by hand.

```bash
cd backend && ./gradlew exportOpenApi
cd mobile && npm run api:generate
```

Commit the regenerated `schema.ts` in the same PR as the backend API change. CI (`contract` job)
regenerates it from the OpenAPI document of the tested backend and fails if it differs from Git.
`types.ts` gives the generated types short names; `client.ts` is the fetch wrapper (bearer token,
one shared refresh on 401, RFC 9457 errors as `ApiError`).
