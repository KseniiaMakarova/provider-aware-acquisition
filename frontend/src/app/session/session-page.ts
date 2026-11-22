import { DecimalPipe, PercentPipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { firstValueFrom, forkJoin } from 'rxjs';

import { AcquisitionApi, errorMessage } from '../api/acquisition-api';
import {
  DecisionDetails,
  PolicyInfo,
  ProviderCharacteristics,
  ProviderInfo,
  ScenarioInfo,
  Session,
} from '../api/models';

/** Validation records that show different behaviour of the adaptive policy (from exploratory runs). */
export const EXAMPLE_RECORDS = ['4', '12', '18', '27', '150'];

/** Upper limit for "Run to end"; every configured provider can be attempted at most once. */
const MAX_AUTOMATIC_STEPS = 10;

export interface ProviderGroupView {
  provider: ProviderCharacteristics;
  features: string[];
  status: 'ACQUIRED' | 'FAILED' | 'UNAVAILABLE' | 'NOT_ACQUIRED';
  failure: string | null;
  values: Record<string, number> | null;
}

/** "REPAYMENT_HISTORY" -> "Repayment history". */
export function providerLabel(id: string): string {
  const words = id.toLowerCase().split('_').join(' ');
  return words.charAt(0).toUpperCase() + words.slice(1);
}

/**
 * Starts an acquisition session and shows, step by step, which provider the policy chose and
 * why, the observed information, the predictions, costs and simulated time, and the stop
 * reason. Hidden values are never available here: the page shows only what the API returns.
 */
@Component({
  selector: 'app-session-page',
  imports: [DecimalPipe, PercentPipe],
  templateUrl: './session-page.html',
  styleUrl: './session-page.css',
})
export class SessionPage {
  private readonly api = inject(AcquisitionApi);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  protected readonly examples = EXAMPLE_RECORDS;
  protected readonly label = providerLabel;

  protected readonly policies = signal<PolicyInfo[]>([]);
  protected readonly scenarios = signal<ScenarioInfo[]>([]);
  protected readonly providerInfo = signal<ProviderInfo[]>([]);

  protected readonly recordId = signal('4');
  protected readonly policyId = signal('ADAPTIVE');
  protected readonly scenarioId = signal('');

  protected readonly session = signal<Session | null>(null);
  /** The decision of the last /advance, or the stored decision when a session is opened. */
  protected readonly decision = signal<DecisionDetails | null>(null);
  protected readonly decisionReason = signal<string | null>(null);

  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly selectedPolicy = computed(() => this.policies().find((p) => p.id === this.policyId()));
  protected readonly selectedScenario = computed(() => this.scenarios().find((s) => s.id === this.scenarioId()));
  protected readonly active = computed(() => this.session()?.status === 'ACTIVE');

  /** Features observed from the start: the observed mask minus every provider group. */
  protected readonly initialFeatures = computed(() => {
    const session = this.session();
    if (!session) {
      return [];
    }
    const providerFeatures = new Set(this.providerInfo().flatMap((p) => p.suppliedFeatures));
    return session.observedMask.filter((f) => !providerFeatures.has(f));
  });

  /** Every provider of the session's scenario with its status and, only if acquired, its values. */
  protected readonly groups = computed<ProviderGroupView[]>(() => {
    const session = this.session();
    if (!session) {
      return [];
    }
    return session.providers.map((provider) => {
      const features = this.providerInfo().find((p) => p.id === provider.id)?.suppliedFeatures ?? [];
      const failedStep = session.steps.find((s) => s.providerId === provider.id && s.status === 'FAILED');
      const acquired = session.acquiredProviders.includes(provider.id);
      const values = acquired
        ? Object.fromEntries(features.map((f) => [f, session.observedFeatures[f]]))
        : null;
      return {
        provider,
        features,
        values,
        failure: failedStep?.failureReason ?? null,
        status: acquired ? 'ACQUIRED' : failedStep ? 'FAILED' : provider.available ? 'NOT_ACQUIRED' : 'UNAVAILABLE',
      };
    });
  });

  /** Which provider each prediction followed. */
  protected readonly predictionRows = computed(() => {
    const session = this.session();
    if (!session) {
      return [];
    }
    return session.predictions.map((p) => {
      const step = session.steps.find((s) => s.predictionSequenceNumber === p.sequenceNumber);
      return { prediction: p, after: step ? providerLabel(step.providerId) : 'Initial information' };
    });
  });

  constructor() {
    forkJoin({ policies: this.api.policies(), scenarios: this.api.scenarios(), providers: this.api.providers() })
      .pipe(takeUntilDestroyed())
      .subscribe({
        next: ({ policies, scenarios, providers }) => {
          this.policies.set(policies);
          this.scenarios.set(scenarios.scenarios);
          this.providerInfo.set(providers);
          if (!policies.some((p) => p.id === this.policyId()) && policies.length > 0) {
            this.policyId.set(policies[0].id);
          }
          this.scenarioId.set(scenarios.defaultScenario);
        },
        error: (e) => this.error.set(errorMessage(e)),
      });

    // A session can be reopened with ?session=<id>, e.g. after a page reload.
    this.route.queryParamMap.pipe(takeUntilDestroyed()).subscribe((params) => {
      const id = params.get('session');
      if (id && id !== this.session()?.sessionId) {
        void this.open(id);
      }
    });
  }

  protected inputValue(event: Event): string {
    return (event.target as HTMLInputElement | HTMLSelectElement).value;
  }

  protected async start(): Promise<void> {
    const recordId = this.recordId().trim();
    if (!recordId) {
      this.error.set('Enter a record ID from the validation split.');
      return;
    }
    await this.run(async () => {
      const session = await firstValueFrom(
        this.api.createSession({
          recordId,
          policyId: this.policyId(),
          ...(this.scenarioId() ? { scenarioId: this.scenarioId() } : {}),
        }),
      );
      this.show(session, null, null);
      await this.router.navigate([], { queryParams: { session: session.sessionId } });
    });
  }

  protected async next(): Promise<void> {
    await this.run(() => this.advanceOnce());
  }

  /** Advances until the policy stops; each request is still one decision of the policy. */
  protected async runToEnd(): Promise<void> {
    await this.run(async () => {
      for (let i = 0; i < MAX_AUTOMATIC_STEPS && this.active(); i++) {
        await this.advanceOnce();
      }
    });
  }

  protected newSession(): void {
    this.session.set(null);
    this.decision.set(null);
    this.decisionReason.set(null);
    this.error.set(null);
    void this.router.navigate([], { queryParams: {} });
  }

  private async open(sessionId: string): Promise<void> {
    await this.run(async () => {
      const session = await firstValueFrom(this.api.session(sessionId));
      const lastStep = session.steps.at(-1);
      this.show(
        session,
        session.stopDecision ?? lastStep?.decisionDetails ?? null,
        session.stopExplanation ?? lastStep?.decisionReason ?? null,
      );
    });
  }

  private async advanceOnce(): Promise<void> {
    const current = this.session();
    if (!current || current.status !== 'ACTIVE') {
      return;
    }
    const response = await firstValueFrom(this.api.advance(current.sessionId));
    this.show(response.session, response.decision, response.decisionReason);
  }

  private show(session: Session, decision: DecisionDetails | null, reason: string | null): void {
    this.session.set(session);
    this.decision.set(decision);
    this.decisionReason.set(reason);
  }

  private async run(action: () => Promise<void>): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    try {
      await action();
    } catch (e) {
      this.error.set(errorMessage(e));
    } finally {
      this.busy.set(false);
    }
  }
}
