import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';

import {
  AdvanceResponse,
  DecisionDetails,
  ProviderCharacteristics,
  ProviderInfo,
  Session,
  StepView,
} from '../api/models';
import { SessionPage, providerLabel } from './session-page';

const FEATURES: Record<string, string[]> = {
  REPAYMENT_HISTORY: ['PAY_0', 'PAY_2', 'PAY_3', 'PAY_4', 'PAY_5', 'PAY_6'],
  BILLING_HISTORY: ['BILL_AMT2', 'BILL_AMT3', 'BILL_AMT4', 'BILL_AMT5', 'BILL_AMT6'],
  PAYMENT_HISTORY: ['PAY_AMT2', 'PAY_AMT3', 'PAY_AMT4', 'PAY_AMT5', 'PAY_AMT6'],
};
const IDS = Object.keys(FEATURES);

function characteristics(id: string, overrides: Partial<ProviderCharacteristics> = {}): ProviderCharacteristics {
  return {
    id,
    description: `${id} description`,
    cost: 1,
    latencyMs: 200,
    latencyJitterMs: 0,
    timeoutMs: null,
    available: true,
    failureRate: 0,
    timeoutProbability: 0,
    expectedLatencyMs: 200,
    successProbability: 1,
    ...overrides,
  };
}

const PROVIDERS: ProviderInfo[] = IDS.map((id) => ({ ...characteristics(id), suppliedFeatures: FEATURES[id] }));

const POLICIES = [
  { id: 'ADAPTIVE', version: 'adaptive-v1', description: 'Adaptive policy', usesValueEstimates: true, parameters: {} },
  { id: 'FIXED_ORDER', version: 'fixed-order-v1', description: 'Fixed order', usesValueEstimates: false, parameters: {} },
  {
    id: 'FULL_ACQUISITION',
    version: 'full-acquisition-v1',
    description: 'Full acquisition',
    usesValueEstimates: false,
    parameters: {},
  },
];

const SCENARIOS = {
  defaultScenario: 'default-v1',
  scenarios: [
    { id: 'default-v1', description: 'Neutral scenario', seed: 42, providers: PROVIDERS },
    {
      id: 'costly-repayment-v1',
      description: 'Repayment is expensive',
      seed: 42,
      providers: PROVIDERS.map((p) => (p.id === 'REPAYMENT_HISTORY' ? { ...p, cost: 10 } : p)),
    },
  ],
};

/** Hidden values of record 4 that must never appear before acquisition (the page never receives them). */
const INITIAL = { LIMIT_BAL: 50000, BILL_AMT1: 46990, PAY_AMT1: 2000 };
const REPAYMENT_VALUES = { PAY_0: 0, PAY_2: 0, PAY_3: 0, PAY_4: 0, PAY_5: 0, PAY_6: 0 };

function session(overrides: Partial<Session> = {}): Session {
  const initialPrediction = {
    sequenceNumber: 0,
    probability: 0.2564,
    observedMask: Object.keys(INITIAL),
    modelVersion: 'mask-hgb-fg-2-d17dd85b',
    createdAt: '2026-11-23T10:00:00Z',
  };
  return {
    sessionId: 's-1',
    recordId: '4',
    status: 'ACTIVE',
    policyId: 'ADAPTIVE',
    datasetSplit: 'validation',
    scenarioVersion: 'default-v1',
    providers: IDS.map((id) => characteristics(id)),
    featureConfigVersion: 'fg-2',
    modelVersion: 'mask-hgb-fg-2-d17dd85b',
    observedFeatures: { ...INITIAL },
    observedMask: Object.keys(INITIAL),
    acquiredProviders: [],
    remainingProviders: [...IDS],
    latestPrediction: initialPrediction,
    predictions: [initialPrediction],
    steps: [],
    totalCost: 0,
    accumulatedSimulatedMs: 0,
    executionMs: 12,
    stopReason: null,
    stopExplanation: null,
    stopDecision: null,
    createdAt: '2026-11-23T10:00:00Z',
    ...overrides,
  };
}

function decision(action: 'ACQUIRE' | 'STOP', selected: string | null, acquired: string[] = []): DecisionDetails {
  const gains: Record<string, number> = { REPAYMENT_HISTORY: 0.0703, BILLING_HISTORY: 0.0011, PAYMENT_HISTORY: 0.0012 };
  return {
    policyId: 'ADAPTIVE',
    policyVersion: 'adaptive-v1',
    parameters: { sampleSize: 100, costWeightBitsPerUnit: 0.01, latencyWeightBitsPerSecond: 0.005 },
    action,
    estimation: {
      method: 'knn-eig-v1',
      sampleSize: 100,
      referenceVersion: 'ref-fg-2-60c26635',
      modelVersion: 'mask-hgb-fg-2-d17dd85b',
      currentProbability: 0.2564,
    },
    candidates: IDS.map((id) => {
      const evaluated = !acquired.includes(id);
      return {
        ...characteristics(id),
        providerId: id,
        status: evaluated ? 'FEASIBLE' : 'ACQUIRED',
        expectedInformationGainBits: evaluated ? gains[id] : null,
        expectedProbability: evaluated ? 0.25 : null,
        expectedAbsoluteChange: evaluated ? 0.05 : null,
        price: evaluated ? 0.011 : null,
        score: evaluated ? gains[id] - 0.011 : null,
        selected: id === selected,
      };
    }),
  };
}

function repaymentStep(): StepView {
  return {
    stepNumber: 1,
    providerId: 'REPAYMENT_HISTORY',
    status: 'SUCCESS',
    decisionReason: 'REPAYMENT_HISTORY has the highest positive score: 0.0593 bits',
    failureType: null,
    failureReason: null,
    acquiredFeatures: REPAYMENT_VALUES,
    chargedCost: 1,
    simulatedLatencyMs: 200,
    predictionSequenceNumber: 1,
    decisionDetails: decision('ACQUIRE', 'REPAYMENT_HISTORY'),
  };
}

function afterRepayment(): Session {
  const base = session();
  const prediction = {
    ...base.latestPrediction,
    sequenceNumber: 1,
    probability: 0.1455,
    observedMask: [...base.observedMask, ...FEATURES['REPAYMENT_HISTORY']],
  };
  return session({
    observedFeatures: { ...INITIAL, ...REPAYMENT_VALUES },
    observedMask: prediction.observedMask,
    acquiredProviders: ['REPAYMENT_HISTORY'],
    remainingProviders: ['BILLING_HISTORY', 'PAYMENT_HISTORY'],
    latestPrediction: prediction,
    predictions: [base.latestPrediction, prediction],
    steps: [repaymentStep()],
    totalCost: 1,
    accumulatedSimulatedMs: 200,
  });
}

const STOP_EXPLANATION = 'No provider is worth its price: the best, PAYMENT_HISTORY, has score -0.0098 bits';

function stopped(): Session {
  return {
    ...afterRepayment(),
    status: 'COMPLETED',
    remainingProviders: [],
    stopReason: 'INSUFFICIENT_EXPECTED_BENEFIT',
    stopExplanation: STOP_EXPLANATION,
    stopDecision: decision('STOP', null, ['REPAYMENT_HISTORY']),
  };
}

/** Lets pending promise continuations run. */
const settle = () => new Promise((resolve) => setTimeout(resolve, 0));

describe('SessionPage', () => {
  let fixture: ComponentFixture<SessionPage>;
  let http: HttpTestingController;
  let element: HTMLElement;

  async function render(): Promise<void> {
    await settle();
    await fixture.whenStable();
  }

  function button(text: string): HTMLButtonElement {
    const found = Array.from(element.querySelectorAll('button')).find((b) => b.textContent?.trim() === text);
    if (!found) {
      throw new Error(`No button "${text}"`);
    }
    return found;
  }

  function select(name: string, value: string): void {
    const field = element.querySelector(`select[name="${name}"]`) as HTMLSelectElement;
    field.value = value;
    field.dispatchEvent(new Event('change'));
  }

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([{ path: '', component: SessionPage }]),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(SessionPage);
    element = fixture.nativeElement;
    http.expectOne('/api/v1/policies').flush({ policies: POLICIES });
    http.expectOne('/api/v1/scenarios').flush(SCENARIOS);
    http.expectOne('/api/v1/providers').flush({ scenarioVersion: 'default-v1', providers: PROVIDERS });
    await render();
  });

  afterEach(() => http.verify());

  it('offers the policies and scenarios of the backend, with the default scenario selected', () => {
    const policies = Array.from(element.querySelectorAll('select[name="policyId"] option')).map((o) => o.textContent);
    expect(policies).toEqual(['ADAPTIVE', 'FIXED_ORDER', 'FULL_ACQUISITION']);
    const scenario = element.querySelector('select[name="scenarioId"]') as HTMLSelectElement;
    expect(scenario.value).toBe('default-v1');
    expect(element.textContent).toContain('Neutral scenario');
    expect(element.textContent).toContain('Adaptive policy');
  });

  it('starts a session with the chosen record, policy and scenario and shows only observed values', async () => {
    button('27').click();
    select('policyId', 'FULL_ACQUISITION');
    select('scenarioId', 'costly-repayment-v1');
    await render();
    expect(element.textContent).toContain('Repayment is expensive');

    button('Start session').click();
    const request = http.expectOne({ method: 'POST', url: '/api/v1/sessions' });
    expect(request.request.body).toEqual({
      recordId: '27',
      policyId: 'FULL_ACQUISITION',
      scenarioId: 'costly-repayment-v1',
    });
    request.flush(session({ recordId: '27', policyId: 'FULL_ACQUISITION', scenarioVersion: 'costly-repayment-v1' }));
    await render();

    expect(element.querySelector('[data-testid="probability"]')?.textContent?.trim()).toBe('25.6%');
    expect(element.textContent).toContain('Record 27');
    expect(element.textContent).toContain('costly-repayment-v1');
    const repayment = element.querySelector('[data-testid="group-REPAYMENT_HISTORY"]')!;
    expect(repayment.textContent).toContain('not acquired');
    expect(repayment.textContent).toContain('6 features, values not acquired');
    expect(repayment.querySelector('dl')).toBeNull();
    expect(TestBed.inject(Router).url).toBe('/?session=s-1');
  });

  it('shows the decision, the acquired values and the new prediction after one step', async () => {
    button('Start session').click();
    http.expectOne('/api/v1/sessions').flush(session());
    await render();

    button('Next step').click();
    const response: AdvanceResponse = {
      outcome: 'ACQUIRED',
      decisionReason: 'REPAYMENT_HISTORY has the highest positive score: 0.0593 bits',
      decision: decision('ACQUIRE', 'REPAYMENT_HISTORY'),
      step: repaymentStep(),
      session: afterRepayment(),
    };
    http.expectOne({ method: 'POST', url: '/api/v1/sessions/s-1/advance' }).flush(response);
    await render();

    const decisionCard = element.querySelector('[data-testid="decision"]')!;
    expect(decisionCard.textContent).toContain('Latest decision: acquire a provider');
    expect(decisionCard.textContent).toContain('0.0703');
    expect(decisionCard.textContent).toContain('0.0593');
    expect(decisionCard.querySelector('tr.selected')?.textContent).toContain('Repayment history');
    expect(element.querySelector('[data-testid="group-REPAYMENT_HISTORY"] dl')?.textContent).toContain('PAY_0');
    expect(element.querySelector('[data-testid="group-BILLING_HISTORY"]')?.textContent).toContain('not acquired');
    expect(element.querySelector('[data-testid="probability"]')?.textContent?.trim()).toBe('14.6%');
    expect(element.querySelector('[data-testid="steps"]')?.textContent).toContain('success');
    expect(element.textContent).toContain('Initial information');
  });

  it('runs to the end with one request per decision and shows why it stopped', async () => {
    button('Start session').click();
    http.expectOne('/api/v1/sessions').flush(session());
    await render();

    button('Run to end').click();
    http.expectOne('/api/v1/sessions/s-1/advance').flush({
      outcome: 'ACQUIRED',
      decisionReason: 'acquire',
      decision: decision('ACQUIRE', 'REPAYMENT_HISTORY'),
      step: repaymentStep(),
      session: afterRepayment(),
    });
    await settle();
    http.expectOne('/api/v1/sessions/s-1/advance').flush({
      outcome: 'STOPPED',
      decisionReason: STOP_EXPLANATION,
      decision: decision('STOP', null, ['REPAYMENT_HISTORY']),
      step: null,
      session: stopped(),
    });
    await render();

    expect(element.querySelector('[data-testid="stop"]')?.textContent).toContain('INSUFFICIENT_EXPECTED_BENEFIT');
    expect(element.querySelector('[data-testid="stop"]')?.textContent).toContain(STOP_EXPLANATION);
    expect(element.textContent).toContain('Latest decision: stop');
    expect(button('Next step').disabled).toBe(true);
    expect(button('Run to end').disabled).toBe(true);
    expect(element.querySelector('[data-testid="cost"]')?.textContent?.trim()).toBe('1');
  });

  it('shows the error of the backend and keeps the form', async () => {
    button('Start session').click();
    http
      .expectOne('/api/v1/sessions')
      .flush(
        { error: 'RECORD_NOT_AVAILABLE', message: 'Record 4 is not available in the validation split' },
        { status: 404, statusText: 'Not Found' },
      );
    await render();

    expect(element.querySelector('[role="alert"]')?.textContent).toContain('RECORD_NOT_AVAILABLE');
    expect(button('Start session')).toBeTruthy();
  });

  it('marks a timed-out provider as failed and shows the timeout', async () => {
    const timedOut: StepView = {
      ...repaymentStep(),
      status: 'FAILED',
      failureType: 'TIMEOUT',
      failureReason: 'Simulated timeout: no response within 1000 ms',
      acquiredFeatures: null,
      chargedCost: 0,
      simulatedLatencyMs: 1000,
      predictionSequenceNumber: null,
    };
    await TestBed.inject(Router).navigateByUrl('/?session=s-1');
    http.expectOne('/api/v1/sessions/s-1').flush(
      session({
        scenarioVersion: 'unreliable-v1',
        providers: IDS.map((id) =>
          characteristics(id, id === 'REPAYMENT_HISTORY' ? { timeoutMs: 1000, latencyJitterMs: 800, latencyMs: 600 } : {}),
        ),
        steps: [timedOut],
        remainingProviders: ['BILLING_HISTORY', 'PAYMENT_HISTORY'],
        accumulatedSimulatedMs: 1000,
      }),
    );
    await render();

    const repayment = element.querySelector('[data-testid="group-REPAYMENT_HISTORY"]')!;
    expect(repayment.textContent).toContain('failed');
    expect(repayment.textContent).toContain('Simulated timeout: no response within 1000 ms');
    expect(repayment.textContent).toContain('timeout 1000 ms');
    expect(element.querySelector('[data-testid="steps"]')?.textContent).toContain('Simulated timeout: no response within 1000 ms');
    // The reopened session shows the stored decision of its last step.
    expect(element.querySelector('[data-testid="decision"]')?.textContent).toContain('Repayment history');
  });
});

describe('providerLabel', () => {
  it('turns provider IDs into readable names', () => {
    expect(providerLabel('REPAYMENT_HISTORY')).toBe('Repayment history');
  });
});
