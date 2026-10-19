# Frontend

Angular 22 application (standalone components, no zone.js, Vitest for tests). It is currently an
application shell with routing; the views for acquisition sessions and experiment results are
planned for the next phase.

## Running

Prerequisite: Node.js 24 (or 22.22 or newer). From `frontend/`:

```bash
npm ci        # once, installs the exact versions from package-lock.json
npm start     # http://localhost:4200
```

The development server forwards requests under `/api` to the backend on http://localhost:8080
(`proxy.conf.json`), so the backend should be running as described in the
[main README](../README.md#running-locally).

Recent npm versions do not run dependency install scripts by default and print a warning about
`esbuild`, `lmdb` and a few others. They are optional native speed-ups; installing, building,
testing and the development server work without them.

## Other commands

| Command | Result |
|---|---|
| `npm test` | Unit tests (Vitest) |
| `npm run build` | Production build in `dist/frontend/browser` |

## Docker image

`Dockerfile` builds the application with Node.js and serves it with nginx. `nginx.conf` forwards
`/api` to the `backend` container and serves `index.html` for application routes. In
`../compose.yaml` the frontend is published on port 4200.
