# Frontend

Angular 22 application (standalone components, signals, no zone.js, Vitest for tests). It has one
page, the acquisition session:

- **Start:** record ID from the validation split, policy and provider scenario, all read from the
  backend (`/api/v1/policies`, `/api/v1/scenarios`), with the scenario's provider characteristics.
- **Session:** current probability of default, providers acquired, calls, total cost and simulated
  time; **Next step** (one `/advance`) and **Run to end** (repeated `/advance` until the policy
  stops, each request still one decision); the stop reason and its explanation.
- **Latest decision:** every provider with its status, cost, expected time and success probability
  and, for the adaptive policy, its expected information gain, price and score.
- **Observed information** grouped by provider: values only for acquired groups, "values not
  acquired" otherwise; failed and timed-out calls with their reason.
- **Prediction history** and **provider calls** with decision reasons, costs and simulated time.

A session can be reopened with `?session=<id>`. All results come from the backend API: the page
does not compute predictions, estimates or scores itself, and it never receives values that have
not been acquired.

Comparison views for experiments are planned for the evaluation phase.

## Running

Prerequisite: Node.js 24.15 or newer (22.22.3 or newer and 26 or newer also work; tested with
24.22.0). From `frontend/`:

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
