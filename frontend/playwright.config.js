import { defineConfig } from '@playwright/test';
import { existsSync, readFileSync, writeFileSync, unlinkSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const fiscalTrust = join(tmpdir(), `sa-trindade-e2e-trust-${process.pid}.p12`);
writeFileSync(fiscalTrust, Buffer.from(readFileSync('../src/test/resources/nfse/test-truststore.base64', 'utf8'), 'base64'), { mode: 0o600 });
process.on('exit', () => { try { unlinkSync(fiscalTrust); } catch { /* Already removed. */ } });

const macChrome = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const externalBase = process.env.E2E_BASE_URL;
if (externalBase && process.env.E2E_ALLOW_EXTERNAL_WRITES !== 'true') {
  throw new Error('Os testes criam registros. Use somente uma instância de teste e defina E2E_ALLOW_EXTERNAL_WRITES=true.');
}
export default defineConfig({
  testDir: './tests',
  fullyParallel: false,
  workers: 1,
  timeout: 60000,
  use: {
    baseURL: externalBase || 'http://127.0.0.1:5174',
    viewport: { width: 1440, height: 1050 },
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    actionTimeout: 10000,
    launchOptions: { ...(process.env.BROWSER_PATH || existsSync(macChrome) ? { executablePath: process.env.BROWSER_PATH || macChrome } : {}) },
  },
  webServer: externalBase ? undefined : [
    {
      command: 'java -jar ../target/emissor-fiscal-0.1.0.jar --server.port=18081 --spring.datasource.url=jdbc:h2:mem:frontend_e2e',
      url: 'http://127.0.0.1:18081/api/me',
      env: {
        APP_ADMIN_PASSWORD: 'frontend-test-password', APP_TENANT_ID: '00000000-0000-0000-0000-000000000099', APP_COOKIE_SECURE: 'false',
        NFSE_MASTER_KEY: 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=', NFSE_ICP_TRUSTSTORE: fiscalTrust,
        NFSE_ICP_TRUSTSTORE_PASSWORD: 'test-only-password', NFSE_CHECK_REVOCATION: 'false', NFSE_ENVIRONMENT: 'HOMOLOGACAO', NFSE_PRODUCTION_ENABLED: 'false',
      },
      reuseExistingServer: false, timeout: 30000, stdout: 'ignore',
    },
    {
      command: process.env.E2E_PREVIEW === 'true' ? 'npm run preview -- --port 5174' : 'npm run dev -- --port 5174',
      url: 'http://127.0.0.1:5174',
      env: { BACKEND_URL: 'http://127.0.0.1:18081' },
      reuseExistingServer: false, timeout: 30000, stdout: 'ignore',
    },
  ],
});
