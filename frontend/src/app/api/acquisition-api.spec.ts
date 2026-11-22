import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';

import { AcquisitionApi, errorMessage } from './acquisition-api';

describe('AcquisitionApi', () => {
  let api: AcquisitionApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    api = TestBed.inject(AcquisitionApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('reads the policies list', async () => {
    const result = firstValueFrom(api.policies());
    http.expectOne({ method: 'GET', url: '/api/v1/policies' }).flush({ policies: [{ id: 'ADAPTIVE' }] });
    expect((await result).map((p) => p.id)).toEqual(['ADAPTIVE']);
  });

  it('reads the scenarios and the providers', async () => {
    const scenarios = firstValueFrom(api.scenarios());
    http.expectOne('/api/v1/scenarios').flush({ defaultScenario: 'default-v1', scenarios: [] });
    expect((await scenarios).defaultScenario).toBe('default-v1');

    const providers = firstValueFrom(api.providers());
    http.expectOne('/api/v1/providers').flush({ scenarioVersion: 'default-v1', providers: [{ id: 'X' }] });
    expect((await providers).map((p) => p.id)).toEqual(['X']);
  });

  it('creates a session with exactly the chosen record, policy and scenario', () => {
    api.createSession({ recordId: '4', policyId: 'ADAPTIVE', scenarioId: 'unreliable-v1' }).subscribe();
    const request = http.expectOne({ method: 'POST', url: '/api/v1/sessions' });
    expect(request.request.body).toEqual({ recordId: '4', policyId: 'ADAPTIVE', scenarioId: 'unreliable-v1' });
    request.flush({});
  });

  it('advances and reads a session by its ID', () => {
    api.advance('a/b').subscribe();
    const advance = http.expectOne({ method: 'POST', url: '/api/v1/sessions/a%2Fb/advance' });
    expect(advance.request.body).toBeNull();
    advance.flush({});

    api.session('s-1').subscribe();
    http.expectOne({ method: 'GET', url: '/api/v1/sessions/s-1' }).flush({});
  });
});

describe('errorMessage', () => {
  it('uses the backend error code and message', () => {
    const error = new HttpErrorResponse({
      status: 404,
      error: { error: 'RECORD_NOT_AVAILABLE', message: 'Record 7 is not available' },
    });
    expect(errorMessage(error)).toBe('RECORD_NOT_AVAILABLE: Record 7 is not available');
  });

  it('explains an unreachable backend and other HTTP errors', () => {
    expect(errorMessage(new HttpErrorResponse({ status: 0 }))).toContain('cannot be reached');
    expect(errorMessage(new HttpErrorResponse({ status: 502, error: 'Bad gateway' }))).toBe(
      'The backend answered HTTP 502.',
    );
    expect(errorMessage(new Error('boom'))).toBe('boom');
  });
});
