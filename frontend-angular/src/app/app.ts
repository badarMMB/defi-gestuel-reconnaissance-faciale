import { Component } from '@angular/core';
import { ChallengeComponent } from './challenge/challenge.component';

@Component({
  selector: 'app-root',
  imports: [ChallengeComponent],
  template: `
    <header>
      <h1>Défi gestuel</h1>
      <p>Prototype : code à 3 chiffres montré avec les doigts, et reconnaissance faciale par CompreFace.</p>
    </header>
    <main><app-challenge /></main>
  `,
  styles: `
    :host { display: block; max-width: 1200px; margin: 0 auto; padding: 16px; }
    header h1 { margin: 0; font-size: 1.5rem; }
    header p { margin: 4px 0 16px; color: var(--muted); }
  `,
})
export class App {}
