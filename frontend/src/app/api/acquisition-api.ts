import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

import {
  AdvanceResponse,
  CreateSessionRequest,
  PolicyInfo,
  ProviderInfo,
  ScenariosResponse,
  Session,
} from './models';

const BASE = '/api/v1';

/** Calls the backend REST API. Every result comes from the backend; nothing is computed here. */
@Injectable({ providedIn: 'root' })
export class AcquisitionApi {
  private readonly http = inject(HttpClient);

  policies(): Observable<PolicyInfo[]> {
    return this.http.get<{ policies: PolicyInfo[] }>(`${BASE}/policies`).pipe(map((r) => r.policies));
  }

  scenarios(): Observable<ScenariosResponse> {
    return this.http.get<ScenariosResponse>(`${BASE}/scenarios`);
  }

  /** Providers with the features they supply (the same in every scenario). */
  providers(): Observable<ProviderInfo[]> {
    return this.http
      .get<{ scenarioVersion: string; providers: ProviderInfo[] }>(`${BASE}/providers`)
      .pipe(map((r) => r.providers));
  }

  createSession(request: CreateSessionRequest): Observable<Session> {
    return this.http.post<Session>(`${BASE}/sessions`, request);
  }

  session(sessionId: string): Observable<Session> {
    return this.http.get<Session>(`${BASE}/sessions/${encodeURIComponent(sessionId)}`);
  }

  /** One decision of the session's policy: acquire one provider or stop. */
  advance(sessionId: string): Observable<AdvanceResponse> {
    return this.http.post<AdvanceResponse>(`${BASE}/sessions/${encodeURIComponent(sessionId)}/advance`, null);
  }
}

/** A readable message for a failed API call, using the backend's {error, message} body when present. */
export function errorMessage(error: unknown): string {
  if (error instanceof HttpErrorResponse) {
    if (error.status === 0) {
      return 'The backend cannot be reached. Is it running?';
    }
    const body = error.error as { error?: string; message?: string } | null;
    if (body && typeof body === 'object' && body.message) {
      return body.error ? `${body.error}: ${body.message}` : body.message;
    }
    return `The backend answered HTTP ${error.status}.`;
  }
  return error instanceof Error ? error.message : String(error);
}
