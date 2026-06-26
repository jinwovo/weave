// Screenshot the provisioned Grafana dashboard (kiosk mode) for the README. Run while k6 drives load
// so the panels show live ingest/sessions. Needs compose up (grafana :3008) + the app + some traffic.
import { chromium } from 'playwright';

const URL = process.env.GRAFANA_URL
  || 'http://localhost:3011/d/weave-main/weave?kiosk&refresh=5s&from=now-5m&to=now';
const OUT = process.env.OUT || '../docs/demo/weave-grafana.png';

const browser = await chromium.launch();
const page = await (await browser.newContext({ viewport: { width: 1280, height: 820 } })).newPage();
await page.goto(URL, { waitUntil: 'networkidle' });
await page.waitForTimeout(12000); // let the panels run a few queries against fresh scrapes
await page.screenshot({ path: OUT });
await browser.close();
console.log('grafana screenshot ->', OUT);
