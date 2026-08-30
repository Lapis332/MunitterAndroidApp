#!/usr/bin/env node

import { mkdir, readdir, writeFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { spawnSync } from 'node:child_process';

const repoRoot = process.cwd();
const expectedPackage = 'com.munitter.android.provisional.development.debug';
const expectedActivity = 'com.munitter.android.MainActivity';
const expectedModel = 'SM_A576Q';
const expectedDevice = 'a57x';
const expectedOrigin = 'https://dev.munitter.com';
const expectedNativeSource = 'android-window-insets-rounded-corner';
const apkPath = path.resolve(
  repoRoot,
  process.env.MUNITTER_ANDROID_GEOMETRY_APK ||
    'app/build/outputs/apk/development/debug/app-development-debug.apk');
const outputDirectory = path.resolve(
  repoRoot,
  process.env.MUNITTER_ANDROID_GEOMETRY_OUTPUT ||
    `artifacts/device-screen-geometry-a57-${new Date().toISOString().replace(/[:.]/g, '-')}`);
const cdpPort = Math.max(
  1024,
  Math.min(65535, Number.parseInt(process.env.MUNITTER_ANDROID_GEOMETRY_CDP_PORT || '9236', 10)));
const skipInstall = /^(1|true|yes)$/i.test(
  process.env.MUNITTER_ANDROID_GEOMETRY_SKIP_INSTALL || 'false');
const clientHeader = process.env.MUNITTER_DEVELOPMENT_CLIENT_HEADER ||
  'MunitterAndroid/0.1.0-development-debug';
const cornerNames = ['topLeft', 'topRight', 'bottomRight', 'bottomLeft'];
const cornerCssVariables = {
  topLeft: ['--device-screen-corner-top-left-x', '--device-screen-corner-top-left-y'],
  topRight: ['--device-screen-corner-top-right-x', '--device-screen-corner-top-right-y'],
  bottomRight: ['--device-screen-corner-bottom-right-x', '--device-screen-corner-bottom-right-y'],
  bottomLeft: ['--device-screen-corner-bottom-left-x', '--device-screen-corner-bottom-left-y'],
};

let selectedSerial = '';
let page = null;
let savedRotation = null;
let forwardInstalled = false;
let result = null;

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

function sleep(milliseconds) {
  return new Promise(resolve => setTimeout(resolve, milliseconds));
}

function resolveAdbPath() {
  const candidates = [
    process.env.MUNITTER_ADB_PATH,
    process.env.ADB,
    process.env.LOCALAPPDATA
      ? path.join(process.env.LOCALAPPDATA, 'Android', 'Sdk', 'platform-tools', 'adb.exe')
      : '',
    'adb',
  ].filter(Boolean);
  for (const candidate of candidates) {
    if (candidate === 'adb' || existsSync(candidate)) return candidate;
  }
  throw new Error('adb was not found');
}

const adbPath = resolveAdbPath();

function run(command, args, options = {}) {
  const response = spawnSync(command, args, {
    cwd: repoRoot,
    encoding: options.binary ? null : 'utf8',
    windowsHide: true,
    timeout: options.timeoutMs || 30_000,
  });
  if (response.status !== 0 && !options.allowFailure) {
    throw new Error(`${options.label || path.basename(command)} failed (exit ${response.status})`);
  }
  return response;
}

function adbGlobal(...args) {
  return run(adbPath, args, { label: 'adb', timeoutMs: 30_000 }).stdout || '';
}

function adb(...args) {
  assert(selectedSerial, 'A57 target has not been selected');
  return run(adbPath, ['-s', selectedSerial, ...args], {
    label: 'adb A57 command',
    timeoutMs: 30_000,
  }).stdout || '';
}

function adbBinary(...args) {
  assert(selectedSerial, 'A57 target has not been selected');
  return run(adbPath, ['-s', selectedSerial, ...args], {
    binary: true,
    label: 'adb A57 binary command',
    timeoutMs: 30_000,
  }).stdout;
}

function parseAttachedA57s() {
  const requestedSerial = process.env.MUNITTER_ANDROID_SERIAL || '';
  const lines = adbGlobal('devices', '-l').split(/\r?\n/);
  return lines.flatMap(line => {
    const match = line.match(/^(.+?)\s+device\s+(.+)$/);
    if (!match) return [];
    const serial = match[1];
    const details = match[2];
    const model = details.match(/(?:^|\s)model:([^\s]+)/)?.[1] || '';
    const device = details.match(/(?:^|\s)device:([^\s]+)/)?.[1] || '';
    if (model !== expectedModel || device !== expectedDevice) return [];
    if (requestedSerial && serial !== requestedSerial) return [];
    return [{ serial, model, device }];
  });
}

function selectPhysicalA57() {
  const candidates = parseAttachedA57s();
  assert(candidates.length > 0, 'No connected physical A57 passed the model/device safety gate');

  const responsive = candidates.flatMap(candidate => {
    const response = run(adbPath, ['-s', candidate.serial, 'shell', 'getprop', 'ro.serialno'], {
      label: 'A57 identity check',
      allowFailure: true,
      timeoutMs: 10_000,
    });
    const hardwareIdentity = String(response.stdout || '').trim();
    return response.status === 0 && hardwareIdentity
      ? [{ ...candidate, hardwareIdentity }]
      : [];
  });
  assert(responsive.length > 0, 'Connected A57 endpoints did not answer the identity check');
  const identities = new Set(responsive.map(candidate => candidate.hardwareIdentity));
  assert(identities.size === 1, 'More than one physical A57 is connected; refusing an ambiguous audit');

  // ADB can advertise the same wireless device more than once. Once the
  // hardware identity proves they are aliases, prefer the canonical route.
  responsive.sort((left, right) => {
    const leftAlias = /\(\d+\)\./.test(left.serial) ? 1 : 0;
    const rightAlias = /\(\d+\)\./.test(right.serial) ? 1 : 0;
    return leftAlias - rightAlias || left.serial.length - right.serial.length;
  });
  selectedSerial = responsive[0].serial;

  const apiLevel = Number.parseInt(adb('shell', 'getprop', 'ro.build.version.sdk').trim(), 10);
  assert(apiLevel >= 31, `A57 API level ${apiLevel} cannot prove RoundedCorner API usage`);
  return { model: expectedModel, device: expectedDevice, apiLevel, physical: true };
}

async function resolveAapt2() {
  const explicit = process.env.MUNITTER_AAPT2_PATH || '';
  if (explicit && existsSync(explicit)) return explicit;
  const buildToolsRoot = process.env.LOCALAPPDATA
    ? path.join(process.env.LOCALAPPDATA, 'Android', 'Sdk', 'build-tools')
    : '';
  if (!buildToolsRoot || !existsSync(buildToolsRoot)) return '';
  const versions = (await readdir(buildToolsRoot, { withFileTypes: true }))
    .filter(entry => entry.isDirectory())
    .map(entry => entry.name)
    .sort((left, right) => right.localeCompare(left, undefined, { numeric: true }));
  for (const version of versions) {
    const candidate = path.join(buildToolsRoot, version, 'aapt2.exe');
    if (existsSync(candidate)) return candidate;
  }
  return '';
}

async function installDevelopmentApk() {
  if (skipInstall) return { skipped: true };
  assert(existsSync(apkPath), `Development APK was not found: ${apkPath}`);
  const aapt2 = await resolveAapt2();
  assert(aapt2, 'aapt2 is required for the Development package safety gate');
  const badging = run(aapt2, ['dump', 'badging', apkPath], {
    label: 'aapt2 APK inspection',
  }).stdout || '';
  const packageName = badging.match(/package:\s+name='([^']+)'/)?.[1] || '';
  assert(packageName === expectedPackage, 'APK package is not the Development Debug application');
  const installOutput = adb('install', '-r', apkPath);
  assert(/Success/i.test(installOutput), 'Development Debug APK update was not accepted');
  return { skipped: false, packageName };
}

async function fetchJson(url, timeoutMs = 20_000) {
  const deadline = Date.now() + timeoutMs;
  let lastError = null;
  while (Date.now() < deadline) {
    try {
      const response = await fetch(url);
      if (response.ok) return response.json();
      lastError = new Error(`HTTP ${response.status}`);
    } catch (error) {
      lastError = error;
    }
    await sleep(250);
  }
  throw new Error(`CDP endpoint did not become ready (${lastError?.message || 'unknown error'})`);
}

class CdpPage {
  constructor(webSocketDebuggerUrl) {
    this.ws = new WebSocket(webSocketDebuggerUrl);
    this.nextId = 0;
    this.pending = new Map();
  }

  async connect() {
    await new Promise((resolve, reject) => {
      const timeout = setTimeout(() => reject(new Error('A57 WebView CDP connect timeout')), 10_000);
      this.ws.onopen = () => {
        clearTimeout(timeout);
        resolve();
      };
      this.ws.onerror = () => reject(new Error('A57 WebView CDP websocket error'));
    });
    this.ws.onmessage = event => {
      const message = JSON.parse(event.data);
      const pending = this.pending.get(message.id);
      if (!pending) return;
      this.pending.delete(message.id);
      if (message.error) pending.reject(new Error(JSON.stringify(message.error)));
      else pending.resolve(message.result);
    };
    await this.call('Page.enable');
    await this.call('Runtime.enable');
    return this;
  }

  call(method, params = {}) {
    return new Promise((resolve, reject) => {
      const id = ++this.nextId;
      const timeout = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`CDP call timed out: ${method}`));
      }, 20_000);
      this.pending.set(id, {
        resolve: value => {
          clearTimeout(timeout);
          resolve(value);
        },
        reject: error => {
          clearTimeout(timeout);
          reject(error);
        },
      });
      this.ws.send(JSON.stringify({ id, method, params }));
    });
  }

  async evaluate(expression) {
    const response = await this.call('Runtime.evaluate', {
      expression,
      returnByValue: true,
      awaitPromise: true,
    });
    if (response?.exceptionDetails) {
      throw new Error(
        response.exceptionDetails.exception?.description ||
        response.exceptionDetails.text ||
        'A57 WebView evaluation failed');
    }
    return response?.result?.value;
  }

  close() {
    try { this.ws.close(); } catch { /* best effort */ }
  }
}

async function connectWebView() {
  adb('shell', 'am', 'force-stop', expectedPackage);
  adb('logcat', '-c');
  adb('shell', 'am', 'start', '-W', '-n', `${expectedPackage}/${expectedActivity}`);
  await sleep(1_500);
  const pid = adb('shell', 'pidof', expectedPackage).trim().split(/\s+/)[0];
  assert(/^\d+$/.test(pid), 'Development Debug application process was not found');
  adbGlobal('-s', selectedSerial, 'forward', `tcp:${cdpPort}`, `localabstract:webview_devtools_remote_${pid}`);
  forwardInstalled = true;
  const targets = await fetchJson(`http://127.0.0.1:${cdpPort}/json`);
  const target = targets.find(item =>
    item.type === 'page' && String(item.url || '').startsWith(expectedOrigin)) ||
    targets.find(item => item.type === 'page');
  assert(target?.webSocketDebuggerUrl, 'Development WebView CDP page was not found');
  page = await new CdpPage(target.webSocketDebuggerUrl).connect();
}

async function waitFor(expression, label, timeoutMs = 25_000) {
  const deadline = Date.now() + timeoutMs;
  let lastValue = null;
  while (Date.now() < deadline) {
    lastValue = await page.evaluate(expression);
    if (lastValue) return lastValue;
    await sleep(200);
  }
  throw new Error(`${label}: ${JSON.stringify(lastValue)}`);
}

async function navigateHomeAndBootstrap() {
  await waitFor(
    `location.origin === ${JSON.stringify(expectedOrigin)} && document.body !== null`,
    'Development origin did not load');
  await page.call('Page.navigate', { url: `${expectedOrigin}/home` });
  await waitFor('document.body !== null && location.origin === "https://dev.munitter.com"',
    'Development home navigation did not load');
  await sleep(700);
  // Always enter the Development-only fixture account so screenshots and the
  // result artifact cannot accidentally contain a tester's personal content.
  const bootstrap = await page.evaluate(`fetch('/internal/dev-test-auth/bootstrap', {
    method: 'POST',
    headers: { 'X-Munitter-Client': ${JSON.stringify(clientHeader)} }
  }).then(async response => ({ ok: response.ok, status: response.status }))`);
  assert(bootstrap?.ok, `Development auth bootstrap failed (${bootstrap?.status || 'unknown'})`);
  await page.call('Page.navigate', { url: `${expectedOrigin}/home` });
  await waitFor(
    `location.pathname === '/home' && document.querySelector('.munitter-main-surface') !== null`,
    'Authenticated Development home did not settle');
  await sleep(900);
}

async function requireDeployedWebGeometryContract() {
  try {
    await waitFor(
      `typeof window.MunitterDeviceScreenGeometry?.setNativeGeometry === 'function' &&
        typeof window.MunitterDeviceScreenGeometry?.getSnapshot === 'function'`,
      'Device Screen Geometry Web contract is not deployed',
      8_000);
  } catch {
    const diagnostic = await page.evaluate(`(() => ({
      contractAvailable: typeof window.MunitterDeviceScreenGeometry?.setNativeGeometry === 'function',
      pendingNativePlatform: window.__munitterPendingDeviceScreenGeometry?.platform || null,
      pendingNativeSource: window.__munitterPendingDeviceScreenGeometry?.source || null,
      loadedScripts: [...document.scripts].map(script => new URL(script.src || location.href).pathname)
        .filter(pathname => pathname.includes('device-screen-geometry')),
    }))()`);
    throw new Error(`Development Web geometry contract is not ready: ${JSON.stringify(diagnostic)}`);
  }
}

const installSnapshotHelper = `(() => {
  const round = value => Number(Number(value || 0).toFixed(4));
  const parseLengthPair = value => {
    const values = String(value || '').match(/-?[0-9.]+/g)?.map(Number) || [0];
    return { x: round(values[0] || 0), y: round(values[1] ?? values[0] ?? 0) };
  };
  const read = () => {
    const geometry = window.MunitterDeviceScreenGeometry?.getSnapshot?.() || null;
    const surface = document.querySelector('.munitter-main-surface[data-device-screen-surface], .munitter-main-surface');
    const panel = document.querySelector('.slide-menu-panel');
    if (!(surface instanceof HTMLElement)) return null;
    const style = getComputedStyle(surface);
    const rootStyle = getComputedStyle(document.documentElement);
    const box = surface.getBoundingClientRect();
    const panelWidth = panel?.getBoundingClientRect().width || panel?.offsetWidth || 1;
    const explicitScale = Number.parseFloat(style.scale);
    const scale = Number.isFinite(explicitScale) && explicitScale > 0
      ? explicitScale
      : surface.offsetWidth > 0 ? box.width / surface.offsetWidth : 1;
    const radius = {
      topLeft: parseLengthPair(style.borderTopLeftRadius),
      topRight: parseLengthPair(style.borderTopRightRadius),
      bottomRight: parseLengthPair(style.borderBottomRightRadius),
      bottomLeft: parseLengthPair(style.borderBottomLeftRadius),
    };
    return {
      at: round(performance.now()),
      href: location.href,
      geometry,
      pendingNative: window.__munitterPendingDeviceScreenGeometry || null,
      rootDataset: {
        platform: document.documentElement.dataset.deviceScreenGeometryPlatform || '',
        source: document.documentElement.dataset.deviceScreenGeometrySource || '',
        confidence: document.documentElement.dataset.deviceScreenGeometryConfidence || '',
        fallback: document.documentElement.dataset.deviceScreenGeometryFallback || '',
        orientation: document.documentElement.dataset.deviceScreenOrientation || '',
      },
      viewport: {
        innerWidth: window.innerWidth,
        innerHeight: window.innerHeight,
        visualWidth: window.visualViewport?.width ?? null,
        visualHeight: window.visualViewport?.height ?? null,
        visualScale: window.visualViewport?.scale ?? null,
        devicePixelRatio: window.devicePixelRatio,
      },
      drawer: {
        open: Boolean(window.MunitterApp?.isMenuOpen),
        bodyOpen: document.body.classList.contains('sidebar-book-open'),
        gesturing: document.body.classList.contains('sidebar-drawer-gesturing'),
        settling: Boolean(window.MunitterApp?.sidebarDrawerGestureController?.getDebugState?.().settling),
        progress: round(Math.max(0, Math.min(1, box.left / Math.max(1, panelWidth)))),
        cssScaleVariable: Number.parseFloat(
          document.body.style.getPropertyValue('--sidebar-main-scale') || '1'),
      },
      surface: {
        dataDeviceScreenSurface: surface.dataset.deviceScreenSurface ?? null,
        scrollMode: surface.dataset.deviceScreenSurfaceScrollMode ?? null,
        position: style.position,
        translate: style.translate,
        scale: round(scale),
        transform: style.transform,
        offsetWidth: surface.offsetWidth,
        offsetHeight: surface.offsetHeight,
        rect: {
          left: round(box.left), top: round(box.top), right: round(box.right), bottom: round(box.bottom),
          width: round(box.width), height: round(box.height),
        },
        radius,
        visualRadius: Object.fromEntries(Object.entries(radius).map(([name, value]) => [name, {
          x: round(value.x * scale), y: round(value.y * scale),
        }])),
      },
      cssVariables: Object.fromEntries([
        '--device-screen-corner-top-left-x', '--device-screen-corner-top-left-y',
        '--device-screen-corner-top-right-x', '--device-screen-corner-top-right-y',
        '--device-screen-corner-bottom-right-x', '--device-screen-corner-bottom-right-y',
        '--device-screen-corner-bottom-left-x', '--device-screen-corner-bottom-left-y',
        '--device-screen-viewport-width', '--device-screen-viewport-height',
      ].map(name => [name, rootStyle.getPropertyValue(name).trim()])),
    };
  };
  window.__munitterA57GeometryAuditSnapshot = read;
  return true;
})()`;

async function installWebAuditHelper() {
  assert(await page.evaluate(installSnapshotHelper), 'Could not install the WebView geometry sampler');
}

async function snapshot() {
  return page.evaluate('window.__munitterA57GeometryAuditSnapshot?.() || null');
}

async function waitForGeometry(orientationPrefix) {
  return waitFor(
    `(() => {
      const geometry = window.MunitterDeviceScreenGeometry?.getSnapshot?.();
      return geometry?.platform === 'android' && geometry?.fallback === false &&
        geometry?.source === ${JSON.stringify(expectedNativeSource)} &&
        geometry?.orientation?.type?.startsWith(${JSON.stringify(orientationPrefix)}) &&
        window.__munitterA57GeometryAuditSnapshot?.();
    })()`,
    `${orientationPrefix} native geometry did not reach the WebView`);
}

async function setImmediateDrawer(open) {
  await page.evaluate(`(() => {
    if (typeof ${open ? 'openMenu' : 'closeMenu'} !== 'function') return false;
    ${open ? 'openMenu' : 'closeMenu'}({ animate: false, reason: 'a57-geometry-audit', source: 'a57-geometry-audit' });
    return true;
  })()`);
  await sleep(100);
  return snapshot();
}

async function captureNaturalMotion(targetOpen) {
  const expression = `(async () => {
    const read = window.__munitterA57GeometryAuditSnapshot;
    if (typeof read !== 'function' || typeof ${targetOpen ? 'openMenu' : 'closeMenu'} !== 'function') return null;
    const frames = [];
    ${targetOpen ? 'openMenu' : 'closeMenu'}({
      animate: true,
      reason: 'a57-geometry-audit-natural-motion',
      source: 'a57-geometry-audit'
    });
    const startedAt = performance.now();
    await new Promise(resolve => {
      const tick = () => {
        const frame = read();
        if (frame) frames.push(frame);
        const debug = window.MunitterApp?.sidebarDrawerGestureController?.getDebugState?.();
        const stable = !debug?.settling && Boolean(window.MunitterApp?.isMenuOpen) === ${targetOpen};
        if ((stable && frames.length > 1) || performance.now() - startedAt > 2500) resolve();
        else requestAnimationFrame(tick);
      };
      requestAnimationFrame(tick);
    });
    return frames;
  })()`;
  const frames = await page.evaluate(expression);
  assert(Array.isArray(frames) && frames.length > 0, 'Natural drawer motion did not produce samples');
  return frames;
}

async function setManualMidpoint(direction) {
  const value = await page.evaluate(`(() => {
    if (typeof applySidebarMenuSwipeProgress !== 'function' ||
      typeof lockSidebarMenuBodyScroll !== 'function') return null;
    lockSidebarMenuBodyScroll();
    clearSidebarMenuSettleTransition?.();
    document.body.classList.add('sidebar-book-swipe-active', 'sidebar-drawer-gesturing');
    document.body.classList.toggle('sidebar-drawer-opening', ${JSON.stringify(direction)} === 'opening');
    document.body.classList.toggle('sidebar-drawer-closing', ${JSON.stringify(direction)} === 'closing');
    applySidebarMenuSwipeProgress(0.5);
    return window.__munitterA57GeometryAuditSnapshot?.() || null;
  })()`);
  assert(value, `${direction} manual midpoint could not be established`);
  return value;
}

async function captureScreenshot(name) {
  const bytes = adbBinary('exec-out', 'screencap', '-p');
  assert(bytes?.length > 1000, `Screenshot ${name} was empty`);
  const filePath = path.join(outputDirectory, `${name}.png`);
  await writeFile(filePath, bytes);
  return filePath;
}

function closestMidpoint(frames) {
  return frames.reduce((best, frame) =>
    !best || Math.abs(frame.drawer.progress - 0.5) < Math.abs(best.drawer.progress - 0.5)
      ? frame
      : best, null);
}

function assertGeometrySnapshot(sample, orientationPrefix, label) {
  assert(sample, `${label}: missing Web snapshot`);
  const geometry = sample.geometry;
  assert(geometry?.platform === 'android', `${label}: native Android geometry is absent`);
  assert(geometry.source === expectedNativeSource, `${label}: OS RoundedCorner source was not selected`);
  assert(geometry.confidence === 'high', `${label}: confidence is not high`);
  assert(geometry.fallback === false, `${label}: fallback was used`);
  assert(geometry.orientation.type.startsWith(orientationPrefix), `${label}: orientation mismatch`);
  assert(geometry.rawNative?.surfaceCoversWindow === true, `${label}: WebView does not cover the window`);
  assert(sample.rootDataset.platform === 'android', `${label}: CSS dataset platform mismatch`);
  assert(sample.rootDataset.fallback === 'false', `${label}: CSS dataset reports fallback`);
  assert(sample.surface.dataDeviceScreenSurface !== null, `${label}: surface is not explicitly scoped`);
  for (const cornerName of cornerNames) {
    const corner = geometry.corners[cornerName];
    assert(corner?.raw?.radius?.x > 0 && corner?.raw?.radius?.y > 0,
      `${label}: ${cornerName} native radius is missing`);
    assert(corner.center && corner.raw.center, `${label}: ${cornerName} center is missing`);
    assert(corner.source === expectedNativeSource, `${label}: ${cornerName} is not OS-native`);
    assert(corner.confidence === 'high', `${label}: ${cornerName} confidence is not high`);
    const [xVariable, yVariable] = cornerCssVariables[cornerName];
    const cssRadius = {
      x: Number.parseFloat(sample.cssVariables[xVariable]),
      y: Number.parseFloat(sample.cssVariables[yVariable]),
    };
    assert(Number.isFinite(cssRadius.x) && Math.abs(cssRadius.x - corner.radius.x) <= 0.75,
      `${label}: ${cornerName} CSS X variable ${cssRadius.x} does not match normalized geometry ${corner.radius.x}`);
    assert(Number.isFinite(cssRadius.y) && Math.abs(cssRadius.y - corner.radius.y) <= 0.75,
      `${label}: ${cornerName} CSS Y variable ${cssRadius.y} does not match normalized geometry ${corner.radius.y}`);
  }
}

function assertSurfaceRadiusState(sample, applied, label) {
  for (const cornerName of cornerNames) {
    const computed = sample.surface.radius[cornerName];
    const expected = applied ? sample.geometry.corners[cornerName].radius : { x: 0, y: 0 };
    assert(Math.abs(computed.x - expected.x) <= 0.75,
      `${label}: ${cornerName} computed X radius ${computed.x} does not match ${applied ? 'active' : 'closed'} value ${expected.x}`);
    assert(Math.abs(computed.y - expected.y) <= 0.75,
      `${label}: ${cornerName} computed Y radius ${computed.y} does not match ${applied ? 'active' : 'closed'} value ${expected.y}`);
  }
}

function assertDrawerState(sample, expectedProgress, label) {
  assert(Math.abs(sample.drawer.progress - expectedProgress) <= 0.08,
    `${label}: drawer progress ${sample.drawer.progress} != ${expectedProgress}`);
  const expectedScale = 1 - expectedProgress * 0.08;
  assert(Math.abs(sample.surface.scale - expectedScale) <= 0.02,
    `${label}: visual scale ${sample.surface.scale} != ${expectedScale}`);
  assertSurfaceRadiusState(sample, expectedProgress > 0.001, label);
  for (const cornerName of cornerNames) {
    const radius = sample.surface.radius[cornerName];
    const visual = sample.surface.visualRadius[cornerName];
    assert(Math.abs(visual.x - radius.x * sample.surface.scale) <= 0.02,
      `${label}: ${cornerName} X radius was not scaled with the surface`);
    assert(Math.abs(visual.y - radius.y * sample.surface.scale) <= 0.02,
      `${label}: ${cornerName} Y radius was not scaled with the surface`);
  }
}

function assertRawCenters(sample, label) {
  const geometry = sample.geometry;
  const bounds = geometry.rawNative.windowBounds;
  for (const cornerName of cornerNames) {
    const raw = geometry.corners[cornerName].raw;
    const left = cornerName === 'topLeft' || cornerName === 'bottomLeft';
    const top = cornerName === 'topLeft' || cornerName === 'topRight';
    const expectedX = left ? bounds.left + raw.radius.x : bounds.right - raw.radius.x;
    const expectedY = top ? bounds.top + raw.radius.y : bounds.bottom - raw.radius.y;
    assert(Math.abs(raw.center.x - expectedX) <= 2,
      `${label}: ${cornerName} native center X is inconsistent with WindowInsets`);
    assert(Math.abs(raw.center.y - expectedY) <= 2,
      `${label}: ${cornerName} native center Y is inconsistent with WindowInsets`);
  }
}

async function auditOrientation(name) {
  const initial = await waitForGeometry(name);
  assertGeometrySnapshot(initial, name, `${name} initial`);
  assertRawCenters(initial, `${name} initial`);

  const closed = await setImmediateDrawer(false);
  assertGeometrySnapshot(closed, name, `${name} closed`);
  assertDrawerState(closed, 0, `${name} closed`);
  const closedScreenshot = await captureScreenshot(`${name}-closed`);

  const naturalOpenFrames = await captureNaturalMotion(true);
  const naturalOpenMid = closestMidpoint(naturalOpenFrames);
  assert(naturalOpenFrames.some(frame => frame.drawer.progress > 0.05 && frame.drawer.progress < 0.95),
    `${name}: natural OPEN motion did not expose an intermediate frame`);
  assertDrawerState(naturalOpenMid, naturalOpenMid.drawer.progress, `${name} natural OPEN midpoint`);

  await setImmediateDrawer(false);
  const openingMid = await setManualMidpoint('opening');
  assertGeometrySnapshot(openingMid, name, `${name} OPEN midpoint`);
  assertDrawerState(openingMid, 0.5, `${name} OPEN midpoint`);
  const openingMidScreenshot = await captureScreenshot(`${name}-opening-midpoint`);

  const opened = await setImmediateDrawer(true);
  assertGeometrySnapshot(opened, name, `${name} open`);
  assertDrawerState(opened, 1, `${name} open`);
  const openScreenshot = await captureScreenshot(`${name}-open`);

  const naturalCloseFrames = await captureNaturalMotion(false);
  const naturalCloseMid = closestMidpoint(naturalCloseFrames);
  assert(naturalCloseFrames.some(frame => frame.drawer.progress > 0.05 && frame.drawer.progress < 0.95),
    `${name}: natural CLOSE motion did not expose an intermediate frame`);
  assertDrawerState(naturalCloseMid, naturalCloseMid.drawer.progress, `${name} natural CLOSE midpoint`);

  await setImmediateDrawer(true);
  const closingMid = await setManualMidpoint('closing');
  assertGeometrySnapshot(closingMid, name, `${name} CLOSE midpoint`);
  assertDrawerState(closingMid, 0.5, `${name} CLOSE midpoint`);
  const closingMidScreenshot = await captureScreenshot(`${name}-closing-midpoint`);

  const finalClosed = await setImmediateDrawer(false);
  assertDrawerState(finalClosed, 0, `${name} final closed`);

  return {
    closed,
    openingMid,
    open: opened,
    closingMid,
    finalClosed,
    naturalOpen: {
      frameCount: naturalOpenFrames.length,
      midpoint: naturalOpenMid,
    },
    naturalClose: {
      frameCount: naturalCloseFrames.length,
      midpoint: naturalCloseMid,
    },
    screenshots: {
      closed: closedScreenshot,
      openingMid: openingMidScreenshot,
      open: openScreenshot,
      closingMid: closingMidScreenshot,
    },
  };
}

function readRotationSetting(name) {
  return adb('shell', 'settings', 'get', 'system', name).trim();
}

async function setRotation(rotation) {
  adb('shell', 'settings', 'put', 'system', 'accelerometer_rotation', '0');
  adb('shell', 'settings', 'put', 'system', 'user_rotation', String(rotation));
  const prefix = rotation === 0 ? 'portrait' : 'landscape';
  await waitFor(
    `(() => {
      const geometry = window.MunitterDeviceScreenGeometry?.getSnapshot?.();
      return geometry?.platform === 'android' && geometry.orientation.type.startsWith(${JSON.stringify(prefix)}) &&
        document.documentElement.dataset.deviceScreenOrientation?.startsWith(${JSON.stringify(prefix)});
    })()`,
    `A57 did not settle into ${prefix}`,
    30_000);
  await sleep(700);
}

async function restoreRotationSettings() {
  if (!savedRotation || !selectedSerial) return false;
  // Lock to the prior user rotation first, then restore the sensor policy. This
  // avoids leaving an auto-rotate device stranded in the audit's landscape.
  adb('shell', 'settings', 'put', 'system', 'accelerometer_rotation', '0');
  if (/^\d+$/.test(savedRotation.userRotation)) {
    adb('shell', 'settings', 'put', 'system', 'user_rotation', savedRotation.userRotation);
  }
  if (/^[01]$/.test(savedRotation.accelerometerRotation)) {
    adb('shell', 'settings', 'put', 'system', 'accelerometer_rotation', savedRotation.accelerometerRotation);
  }
  await sleep(1_000);
  return readRotationSetting('accelerometer_rotation') === savedRotation.accelerometerRotation &&
    readRotationSetting('user_rotation') === savedRotation.userRotation;
}

function collectNativeGeometryLogs() {
  const raw = adb('logcat', '-d', '-s', 'DeviceScreenGeometry:I', '*:S');
  const lines = raw.split(/\r?\n/).filter(line => line.includes('DeviceScreenGeometry'));
  assert(lines.some(line => line.includes(`source=${expectedNativeSource}`) &&
    line.includes('fallback=false') && line.includes('confidence=high')),
  'Development logcat did not prove OS RoundedCorner delivery');
  return lines;
}

async function main() {
  await mkdir(outputDirectory, { recursive: true });
  const device = selectPhysicalA57();
  const install = await installDevelopmentApk();
  savedRotation = {
    accelerometerRotation: readRotationSetting('accelerometer_rotation'),
    userRotation: readRotationSetting('user_rotation'),
  };
  await connectWebView();
  await navigateHomeAndBootstrap();
  await requireDeployedWebGeometryContract();
  await installWebAuditHelper();

  await setRotation(0);
  const portrait = await auditOrientation('portrait');
  await setRotation(1);
  const landscape = await auditOrientation('landscape');
  const nativeLogs = collectNativeGeometryLogs();

  const portraitBounds = portrait.closed.geometry.rawNative.windowBounds;
  const landscapeBounds = landscape.closed.geometry.rawNative.windowBounds;
  assert(portraitBounds.width === landscapeBounds.height &&
    portraitBounds.height === landscapeBounds.width,
  'Portrait and landscape window bounds did not rotate as one physical display');

  return {
    passed: true,
    productionTouched: false,
    origin: expectedOrigin,
    package: expectedPackage,
    device,
    install,
    portrait,
    landscape,
    nativeGeometryLogs: nativeLogs,
  };
}

let exitCode = 0;
try {
  result = await main();
} catch (error) {
  exitCode = 1;
  result = {
    passed: false,
    productionTouched: false,
    origin: expectedOrigin,
    package: expectedPackage,
    error: String(error?.stack || error),
  };
} finally {
  try {
    if (page) await setImmediateDrawer(false).catch(() => null);
  } catch { /* best effort */ }
  try {
    result.rotationRestored = await restoreRotationSettings();
    if (savedRotation) assert(result.rotationRestored, 'A57 rotation settings were not restored');
  } catch (error) {
    exitCode = 1;
    result.rotationRestored = false;
    result.rotationRestoreError = String(error?.message || error);
  }
  try { page?.close(); } catch { /* best effort */ }
  try {
    if (forwardInstalled) adbGlobal('-s', selectedSerial, 'forward', '--remove', `tcp:${cdpPort}`);
  } catch { /* best effort */ }
  await mkdir(outputDirectory, { recursive: true });
  const outputPath = path.join(outputDirectory, 'result.json');
  await writeFile(outputPath, `${JSON.stringify(result, null, 2)}\n`, 'utf8');
  if (exitCode === 0 && result.passed && result.rotationRestored) {
    process.stdout.write(`A57 Device Screen Geometry audit: PASS ${outputPath}\n`);
  } else {
    process.stderr.write(`A57 Device Screen Geometry audit: FAIL ${outputPath}\n${result.error || result.rotationRestoreError || ''}\n`);
    process.exitCode = 1;
  }
}
