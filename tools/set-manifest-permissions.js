#!/usr/bin/env node
/**
 * set-manifest-permissions.js — remove permissions from AndroidManifest.xml.
 *
 * The template declares every permission the app can use. This takes out the
 * ones a client should not have, and removes the <uses-feature> entries that
 * only existed because of them.
 *
 *   node tools/set-manifest-permissions.js CAMERA
 *   node tools/set-manifest-permissions.js CAMERA RECORD_AUDIO LOCATION
 *   node tools/set-manifest-permissions.js            # show what is declared
 *
 * It writes as soon as you run it. git is the undo:
 *   git diff                  review
 *   git checkout -- <file>    put it back
 *
 * No dependencies. Node 18+.
 */

'use strict';

const fs = require('node:fs');
const path = require('node:path');

const PERMISSION = 'android.permission.';
const FEATURE = 'android.hardware.';

/* ------------------------------------------------------------------ *
 * What to remove
 * ------------------------------------------------------------------ */

/**
 * One keyword that removes several permissions, for the things that are always
 * asked for together. The location permissions are the reason this exists: an
 * app that has no use for location should not keep one of them.
 */
const GROUPS = {
  LOCATION: ['ACCESS_FINE_LOCATION', 'ACCESS_COARSE_LOCATION'],
  MICROPHONE: ['RECORD_AUDIO'],
  BLUETOOTH: ['BLUETOOTH', 'BLUETOOTH_ADMIN', 'BLUETOOTH_SCAN', 'BLUETOOTH_CONNECT', 'BLUETOOTH_ADVERTISE'],
  STORAGE: ['READ_EXTERNAL_STORAGE', 'WRITE_EXTERNAL_STORAGE', 'MANAGE_EXTERNAL_STORAGE',
    'READ_MEDIA_IMAGES', 'READ_MEDIA_VIDEO', 'READ_MEDIA_AUDIO', 'ACCESS_MEDIA_LOCATION'],
  PHONE: ['CALL_PHONE', 'READ_PHONE_STATE', 'READ_PHONE_NUMBERS', 'PROCESS_OUTGOING_CALLS', 'ANSWER_PHONE_CALLS'],
  SMS: ['SEND_SMS', 'RECEIVE_SMS', 'READ_SMS', 'RECEIVE_MMS', 'RECEIVE_WAP_PUSH'],
  CONTACTS: ['READ_CONTACTS', 'WRITE_CONTACTS', 'GET_ACCOUNTS'],
  CALENDAR: ['READ_CALENDAR', 'WRITE_CALENDAR'],
  CALL_LOG: ['READ_CALL_LOG', 'WRITE_CALL_LOG'],
  SENSORS: ['BODY_SENSORS', 'ACTIVITY_RECOGNITION', 'HIGH_SAMPLING_RATE_SENSORS'],
  BIOMETRIC: ['USE_BIOMETRIC', 'USE_FINGERPRINT'],
};

/**
 * Features a permission implies, from Android's own table. Declaring the
 * permission is what declares the hardware need, so dropping the permission
 * without dropping these leaves the manifest asking for hardware the app has
 * just said it does not use — and keeps the Play Store filter they create.
 */
const IMPLIES = {
  ACCESS_COARSE_LOCATION: ['location', 'location.network'],
  ACCESS_FINE_LOCATION: ['location', 'location.gps'],
  ACCESS_LOCATION_EXTRA_COMMANDS: ['location'],
  ACCESS_MOCK_LOCATION: ['location'],
  ACCESS_WIFI_STATE: ['wifi'],
  BLUETOOTH: ['bluetooth'],
  BLUETOOTH_ADMIN: ['bluetooth'],
  BLUETOOTH_SCAN: ['bluetooth_le'],
  CALL_PHONE: ['telephony'],
  CAMERA: ['camera', 'camera.autofocus'],
  CHANGE_WIFI_STATE: ['wifi'],
  NFC: ['nfc'],
  PROCESS_OUTGOING_CALLS: ['telephony'],
  READ_PHONE_STATE: ['telephony'],
  READ_SMS: ['telephony'],
  RECEIVE_MMS: ['telephony'],
  RECEIVE_SMS: ['telephony'],
  RECEIVE_WAP_PUSH: ['telephony'],
  RECORD_AUDIO: ['microphone'],
  SEND_SMS: ['telephony'],
};

/** Permissions that imply no feature, accepted as a bare name. */
const SINGLE = new Set([
  'ACCESS_BACKGROUND_LOCATION', 'ACCESS_NETWORK_STATE', 'ACTIVITY_RECOGNITION',
  'BLUETOOTH_ADVERTISE', 'BLUETOOTH_CONNECT', 'CHANGE_NETWORK_STATE',
  'DOWNLOAD_WITHOUT_NOTIFICATION', 'EXPAND_STATUS_BAR',
  'FOREGROUND_SERVICE', 'GET_ACCOUNTS', 'HIGH_SAMPLING_RATE_SENSORS',
  'INTERNET', 'KILL_BACKGROUND_PROCESSES', 'MANAGE_EXTERNAL_STORAGE',
  'MODIFY_AUDIO_SETTINGS', 'POST_NOTIFICATIONS', 'QUERY_ALL_PACKAGES',
  'READ_CALENDAR', 'READ_CALL_LOG', 'READ_CONTACTS', 'READ_EXTERNAL_STORAGE',
  'READ_MEDIA_AUDIO', 'READ_MEDIA_IMAGES', 'READ_MEDIA_VIDEO',
  'READ_PHONE_NUMBERS', 'RECEIVE_BOOT_COMPLETED',
  'REQUEST_IGNORE_BATTERY_OPTIMIZATIONS', 'REQUEST_INSTALL_PACKAGES',
  'SCHEDULE_EXACT_ALARM', 'SET_ALARM', 'SYSTEM_ALERT_WINDOW', 'TRANSMIT_IR',
  'USE_BIOMETRIC', 'USE_EXACT_ALARM', 'USE_FINGERPRINT',
  'USE_FULL_SCREEN_INTENT', 'VIBRATE', 'WAKE_LOCK',
  'WRITE_CALENDAR', 'WRITE_CALL_LOG', 'WRITE_CONTACTS', 'WRITE_EXTERNAL_STORAGE',
]);

/** The features a permission implies, fully qualified. */
const implies = (permission) =>
  (IMPLIES[permission.slice(PERMISSION.length)] ?? []).map((f) => FEATURE + f);

/** A keyword or name the user typed -> the permissions it removes. */
function expand(arg, unknown) {
  const word = arg.startsWith('-') ? arg.slice(1) : arg;

  if (GROUPS[word]) return GROUPS[word].map((p) => PERMISSION + p);
  if (IMPLIES[word] || SINGLE.has(word)) return [PERMISSION + word];
  if (word.includes('.')) return [word]; // a full name, e.g. com.android.vending.BILLING

  unknown.push(word);
  return [];
}

/* ------------------------------------------------------------------ *
 * Reading the manifest
 * ------------------------------------------------------------------ */

const ELEMENT = /^(?<indent>[ \t]*)<(?<tag>uses-permission(?:-sdk-\d+)?|uses-feature)(?<attrs>(?:\s+[^<>]*?)?)\/?>/;
const NAME_ATTR = /\bandroid:name\s*=\s*"([^"]*)"/;

function parse(content) {
  const lines = content.split(/\r?\n/);
  const found = new Map(); // full name -> line index
  let indent = '    ';
  const split = [];

  lines.forEach((line, index) => {
    if (/<uses-(permission|feature)\b[^>]*$/.test(line)) split.push(index + 1);

    const match = ELEMENT.exec(line);
    if (!match) return;
    const name = NAME_ATTR.exec(match.groups.attrs);
    if (!name) return;

    indent = match.groups.indent;
    found.set(name[1], { line: index, kind: match.groups.tag === 'uses-feature' ? 'feature' : 'permission' });
  });

  return { lines, found, indent, split };
}

/* ------------------------------------------------------------------ *
 * Locating the manifest
 * ------------------------------------------------------------------ */

const SKIP = new Set(['.git', '.gradle', '.idea', 'build', 'node_modules']);

function findManifest(start) {
  // Walk up, so it works from tools/, from the project root, or from anywhere
  // inside the project — not just from the one directory that has app/ in it.
  let dir = path.resolve(start);
  for (;;) {
    const found = search(path.join(dir, 'app', 'src'));
    if (found) return found;
    const parent = path.dirname(dir);
    if (parent === dir) return null;
    dir = parent;
  }
}

function search(root) {
  const hits = [];

  (function walk(dir) {
    let entries;
    try {
      entries = fs.readdirSync(dir, { withFileTypes: true });
    } catch {
      return;
    }
    for (const entry of entries) {
      if (entry.isSymbolicLink()) continue;
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) {
        if (!SKIP.has(entry.name)) walk(full);
      } else if (entry.name === 'AndroidManifest.xml') {
        hits.push(full);
      }
    }
  })(root);

  // app/src/main/ wins; that is the one a client build uses.
  return hits.find((f) => /[\\/]main[\\/]/.test(f)) ?? hits[0] ?? null;
}

/* ------------------------------------------------------------------ *
 * Main
 * ------------------------------------------------------------------ */

const dim = (s) => (process.stdout.isTTY && !process.env.NO_COLOR ? `\x1b[2m${s}\x1b[0m` : s);

function fail(message) {
  console.error(`\nerror: ${message}\n`);
  return 1;
}

const HELP = `
set-manifest-permissions.js — remove permissions from AndroidManifest.xml.

USAGE
  node tools/set-manifest-permissions.js NAME [NAME ...]

  Run it with no names to list what the manifest declares.

NAMES
  CAMERA  RECORD_AUDIO  MICROPHONE  LOCATION  BLUETOOTH  STORAGE  PHONE
  SMS  CONTACTS  CALENDAR  CALL_LOG  SENSORS  BIOMETRIC  INTERNET
  ACCESS_NETWORK_STATE  MODIFY_AUDIO_SETTINGS  VIBRATE  POST_NOTIFICATIONS
  WAKE_LOCK  and the rest of the usual Android permissions.

  A full name such as com.android.vending.BILLING works too.

USES-FEATURE
  Removing a permission also removes the <uses-feature> entries only it
  implies. Android's own table:

    CAMERA        -> hardware.camera, hardware.camera.autofocus
    RECORD_AUDIO  -> hardware.microphone
    LOCATION      -> hardware.location, hardware.location.gps,
                     hardware.location.network
    BLUETOOTH     -> hardware.bluetooth, hardware.bluetooth_le
    NFC           -> hardware.nfc
    PHONE / SMS   -> hardware.telephony
    WIFI          -> hardware.wifi

  A feature goes only when nothing left implies it, so LOCATION removes both
  location permissions and then both location features. A feature the manifest
  does not declare is simply nothing to remove — this never adds a line.

OPTIONS
  --manifest <file>  Edit this manifest instead of finding one.
  --dry-run          Show what would go, write nothing.
  --help             This text.
`;

function main(argv) {
  let manifestArg = null;
  let dryRun = false;
  const names = [];

  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === '--help' || arg === '-h') return console.log(HELP.trim()), 0;
    else if (arg === '--dry-run') dryRun = true;
    else if (arg === '--manifest') manifestArg = argv[++i];
    else names.push(arg);
  }

  /* locate */
  const manifest = manifestArg
    ? path.resolve(manifestArg)
    : findManifest(process.cwd());

  if (!manifest || !fs.existsSync(manifest)) {
    const tried = manifestArg ? manifestArg : path.join(process.cwd(), 'app', 'src');
    return fail(`no AndroidManifest.xml at ${tried}\n       run this from the project root, or pass --manifest <file>`);
  }

  // Relative when it stays inside the working directory, absolute when it does not.
  const relative = path.relative(process.cwd(), manifest);
  const shown = relative && !relative.startsWith('..') ? relative : manifest;
  const original = fs.readFileSync(manifest, 'utf8');
  const parsed = parse(original);

  /* list mode */
  if (names.length === 0) {
    const permissions = [...parsed.found].filter(([, v]) => v.kind === 'permission');
    const features = [...parsed.found].filter(([, v]) => v.kind === 'feature');

    console.log(`\n${shown}\n`);
    console.log(`Permissions (${permissions.length})`);
    for (const [name] of permissions) console.log(`  ${name.replace(PERMISSION, '')}`);
    console.log(`\nFeatures (${features.length})`);
    for (const [name] of features) console.log(`  ${name.replace(FEATURE, '')}`);
    console.log(`\nRemove one:  node tools/set-manifest-permissions.js CAMERA\n`);
    return 0;
  }

  /* work out the lines to drop */
  if (parsed.split.length) {
    return fail(
      `${shown} has a <uses-permission> or <uses-feature> split over several lines\n` +
      `       (line ${parsed.split.join(', ')}). Put each element on one line first.`,
    );
  }

  const unknown = [];
  const permissions = new Set();
  for (const name of names) for (const p of expand(name, unknown)) permissions.add(p);

  if (unknown.length) {
    return fail(
      `do not know ${unknown.map((u) => `'${u}'`).join(', ')}\n` +
      `       names are CAMERA, RECORD_AUDIO, LOCATION, BLUETOOTH, STORAGE, PHONE, SMS,\n` +
      `       CONTACTS, CALENDAR, CALL_LOG, SENSORS, BIOMETRIC, or any permission such as\n` +
      `       INTERNET, VIBRATE, MODIFY_AUDIO_SETTINGS — or a full name with a dot in it.`,
    );
  }

  // A feature survives if a permission that is staying still implies it.
  const survivors = new Set();
  for (const [name, entry] of parsed.found) {
    if (entry.kind === 'permission' && !permissions.has(name)) {
      for (const feature of implies(name)) survivors.add(feature);
    }
  }

  const features = new Set();
  for (const permission of permissions) {
    for (const feature of implies(permission)) if (!survivors.has(feature)) features.add(feature);
  }

  const drop = new Map(); // line index -> full name
  for (const name of permissions) if (parsed.found.has(name)) drop.set(parsed.found.get(name).line, name);
  for (const name of features) if (parsed.found.has(name)) drop.set(parsed.found.get(name).line, name);

  const missing = [...permissions, ...features].filter((n) => !parsed.found.has(n));

  /* report */
  console.log(`\n${shown}`);
  if (drop.size === 0) {
    for (const name of missing) console.log(`  ${dim('skipped')}  ${name} is not declared`);
    console.log(`\n  nothing to remove\n`);
    return 0;
  }

  const width = Math.max(...[...drop.values()].map((n) => n.length));
  console.log('');
  for (const name of [...drop.values()].sort()) {
    console.log(`  ${name.padEnd(width)}  ${dim('removed')}`);
  }
  for (const name of missing) console.log(`  ${dim(`skipped  ${name} is not declared`)}`);

  if (dryRun) {
    console.log(`\n  dry run — nothing written\n`);
    return 0;
  }

  /* write */
  const kept = parsed.lines.filter((_, index) => !drop.has(index));
  if (parsed.lines.length - kept.length !== drop.size) {
    return fail(`internal check failed: expected to drop ${drop.size} lines, dropped ${parsed.lines.length - kept.length}`);
  }

  const eol = original.includes('\r\n') ? '\r\n' : '\n';
  const hadFinalNewline = /\r?\n$/.test(original);
  const text = kept.join(eol);
  fs.writeFileSync(manifest, hadFinalNewline ? text : text.replace(/\r?\n$/, ''), 'utf8');

  /* confirm, by reading the file back */
  const after = parse(fs.readFileSync(manifest, 'utf8'));
  const left = [...drop.values()].filter((name) => after.found.has(name));
  if (left.length) {
    return fail(`wrote ${shown} but ${left.join(', ')} is still there — check it with: git diff -- ${shown}`);
  }

  const gone = [...drop.values()].filter((n) => n.startsWith(PERMISSION)).length;
  console.log(`\n  ${gone} permission(s) and ${drop.size - gone} feature(s) removed.`);
  console.log(`  Undo:  git checkout -- ${shown}\n`);
  return 0;
}

process.exitCode = main(process.argv.slice(2));
