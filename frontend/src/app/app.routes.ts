import { Routes } from '@angular/router';

import { SessionPage } from './session/session-page';

export const routes: Routes = [
  { path: '', component: SessionPage, title: 'Acquisition session' },
  { path: '**', redirectTo: '' },
];
