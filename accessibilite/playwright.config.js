// Contrôles d'accessibilité (RGAA 4.1.2). Les interfaces n'ont qu'un thème clair : un test par interface vérifie
// qu'elles le gardent quand le système est réglé en sombre.
const { defineConfig } = require('@playwright/test');

module.exports = defineConfig({
  testDir: 'tests',
  forbidOnly: !!process.env.CI,
  retries: 0,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: { browserName: 'chromium', trace: 'off' },
  projects: [
    { name: 'clair', use: { colorScheme: 'light' } },
  ],
});
