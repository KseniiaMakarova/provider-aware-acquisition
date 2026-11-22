/** Types of the backend REST API (/api/v1). */

export interface PolicyInfo {
  id: string;
  version: string;
  description: string;
  usesValueEstimates: boolean;
  parameters: Record<string, unknown>;
}

/** Characteristics of a provider in one scenario. */
export interface ProviderCharacteristics {
  id: string;
  description: string;
  cost: number;
  latencyMs: number;
  latencyJitterMs: number;
  timeoutMs: number | null;
  available: boolean;
  failureRate: number;
  timeoutProbability: number;
  expectedLatencyMs: number;
  successProbability: number;
}

/** A provider with the features it supplies (from /providers and /scenarios). */
export interface ProviderInfo extends ProviderCharacteristics {
  suppliedFeatures: string[];
}

export interface ScenarioInfo {
  id: string;
  description: string;
  seed: number;
  providers: ProviderInfo[];
}

export interface ScenariosResponse {
  defaultScenario: string;
  scenarios: ScenarioInfo[];
}

export interface DecisionCandidate {
  providerId: string;
  status: 'FEASIBLE' | 'ACQUIRED' | 'FAILED_EARLIER' | 'UNAVAILABLE';
  cost: number;
  latencyMs: number;
  latencyJitterMs: number;
  timeoutMs: number | null;
  failureRate: number;
  timeoutProbability: number;
  successProbability: number;
  expectedLatencyMs: number;
  expectedInformationGainBits: number | null;
  expectedProbability: number | null;
  expectedAbsoluteChange: number | null;
  price: number | null;
  score: number | null;
  selected: boolean;
}

export interface DecisionDetails {
  policyId: string;
  policyVersion: string;
  parameters: Record<string, unknown>;
  action: 'ACQUIRE' | 'STOP';
  estimation: {
    method: string;
    sampleSize: number;
    referenceVersion: string;
    modelVersion: string;
    currentProbability: number;
  } | null;
  candidates: DecisionCandidate[];
}

export interface PredictionView {
  sequenceNumber: number;
  probability: number;
  observedMask: string[];
  modelVersion: string;
  createdAt: string;
}

export interface StepView {
  stepNumber: number;
  providerId: string;
  status: 'SUCCESS' | 'FAILED';
  decisionReason: string;
  failureType: 'FAILURE' | 'TIMEOUT' | null;
  failureReason: string | null;
  acquiredFeatures: Record<string, number> | null;
  chargedCost: number;
  simulatedLatencyMs: number;
  predictionSequenceNumber: number | null;
  decisionDetails: DecisionDetails | null;
}

export interface Session {
  sessionId: string;
  recordId: string;
  status: 'ACTIVE' | 'COMPLETED' | 'FAILED';
  policyId: string;
  datasetSplit: string;
  scenarioVersion: string;
  providers: ProviderCharacteristics[];
  featureConfigVersion: string;
  modelVersion: string;
  observedFeatures: Record<string, number>;
  observedMask: string[];
  acquiredProviders: string[];
  remainingProviders: string[];
  latestPrediction: PredictionView;
  predictions: PredictionView[];
  steps: StepView[];
  totalCost: number;
  accumulatedSimulatedMs: number;
  executionMs: number;
  stopReason: string | null;
  stopExplanation: string | null;
  stopDecision: DecisionDetails | null;
  createdAt: string;
}

export interface AdvanceResponse {
  outcome: 'ACQUIRED' | 'PROVIDER_FAILED' | 'STOPPED';
  decisionReason: string;
  decision: DecisionDetails | null;
  step: StepView | null;
  session: Session;
}

export interface CreateSessionRequest {
  recordId: string;
  policyId: string;
  scenarioId?: string;
}
