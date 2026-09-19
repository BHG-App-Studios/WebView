#!/usr/bin/env node
/**
 * set-app-config.js — read and change the AppConfig.kt values, in one run.
 *
 * AppConfig.kt is the one file every white-label build is configured from: the
 * home URL, the progress-bar switches, the feature toggles and the numeric
 * tunables. Hand-editing it is how typos ship — `ture` for `true`, a URL with
 * no scheme, a timeout that overflows Int, a renamed constant that silently
 * stops being patched.
 *
 * This script takes the file as the authority. It works out each constant's
 * type from its own declaration, validates the value against that type,
 * rewrites only the literals that actually change, then re-reads the file to
 * prove the values landed and that nothing else moved.
 *
 * It writes as soon as you run it. Pass --dry-run to see the plan instead.
 * Undo is git's job:  git diff  to review,  git checkout -- <file>  to revert.
 *
 * Usage:
 *   node tools/set-app-config.js HOME_URL=https://mysite.com
 *   node tools/set-app-config.js ENABLE_DOWNLOADS=false PAGE_LOAD_TIMEOUT_MS=45000
 *   node tools/set-app-config.js --list
 *   node tools/set-app-config.js --json payload.json
 *
 * Run with --help for the full option list. No dependencies; Node 18+.
 */

'use strict';

const fs = require('node:fs');
const path = require('node:path');
const process = require('node:process');

/* ------------------------------------------------------------------ *
 * Constants
 * ------------------------------------------------------------------ */

/** Directories never walked: VCS, caches and build output. */
const IGNORED_DIRS = new Set([
  '.git', '.hg', '.svn',
  '.gradle', '.idea', '.kotlin', '.vs', '.vscode',
  'build', 'out', 'node_modules', 'captures', '.cxx',
]);

/** The Kotlin types this script knows how to write. */
const SUPPORTED_TYPES = new Set(['Boolean', 'Int', 'Long', 'String']);

const INT_MIN = -2147483648;
const INT_MAX = 2147483647;

const TRUE_WORDS = new Set(['true', 'yes', 'on', '1', 'enable', 'enabled']);
const FALSE_WORDS = new Set(['false', 'no', 'off', '0', 'disable', 'disabled']);

/**
 * `const val NAME: Type = literal`, all on one line.
 *
 *   indent  leading whitespace
 *   head    everything up to and including the `= ` — kept verbatim so the
 *           rewritten line is byte-identical apart from the literal
 *   name    the constant's identifier
 *   type    the declared Kotlin type
 *   value   the literal, plus any trailing comment (split off separately)
 */
const DECLARATION = new RegExp(
  '^(?<indent>[ \\t]*)' +
  '(?<head>(?:const[ \\t]+)?val[ \\t]+' +
  '(?<name>[A-Za-z_][A-Za-z0-9_]*)[ \\t]*:[ \\t]*' +
  '(?<type>[A-Za-z_][A-Za-z0-9_.]*(?:<[^>]*>)?)[ \\t]*=[ \\t]*)' +
  '(?<value>.*)$',
);

/** A `val NAME = ...` with no `: Type` — recognised only to explain the refusal. */
const DECLARATION_NO_TYPE =
  /^[ \t]*(?:const[ \t]+)?val[ \t]+(?<name>[A-Za-z_][A-Za-z0-9_]*)[ \t]*=/;

/* ------------------------------------------------------------------ *
 * Small utilities
 * ------------------------------------------------------------------ */

const useColor = process.stdout.isTTY && !process.env.NO_COLOR;
const paint = (code) => (s) => (useColor ? `\x1b[${code}m${s}\x1b[0m` : String(s));
const bold = paint('1');
const dim = paint('2');
const red = paint('31');
const green = paint('32');
const yellow = paint('33');
const cyan = paint('36');

const log = (...a) => console.log(...a);

function fail(message) {
  console.error(`\n${red('error:')} ${message}\n`);
  process.exit(1);
}

function warn(message) {
  console.error(`${yellow('warning:')} ${message}`);
}

function toPosix(p) {
  return p.split(path.sep).join('/');
}

/** A path for humans: relative when it stays inside the working directory. */
function display(p) {
  const relative = path.relative(process.cwd(), p);
  if (relative === '' || relative.startsWith('..') || path.isAbsolute(relative)) return p;
  return toPosix(relative);
}

function readText(file) {
  return fs.readFileSync(file, 'utf8');
}

function writeText(file, text) {
  fs.writeFileSync(file, text, 'utf8');
}

/** Thrown by the value coercers; turned into a clean error message. */
class BadValue extends Error {}

/* ------------------------------------------------------------------ *
 * Locating the project and the config file
 * ------------------------------------------------------------------ */

/** Walk up from `start` until a Gradle project root is found. */
function findProjectRoot(start) {
  let dir = path.resolve(start);
  const markers = ['settings.gradle', 'settings.gradle.kts', 'app/build.gradle', 'app/build.gradle.kts'];

  for (;;) {
    if (markers.some((marker) => fs.existsSync(path.join(dir, marker)))) return dir;
    const parent = path.dirname(dir);
    if (parent === dir) return null;
    dir = parent;
  }
}

/** Every AppConfig.kt under app/src, the main source set first. */
function findAppConfigFiles(projectDir) {
  const sourceRoot = path.join(projectDir, 'app', 'src');
  if (!fs.existsSync(sourceRoot)) return [];

  const hits = [];

  (function visit(dir) {
    let entries;
    try {
      entries = fs.readdirSync(dir, { withFileTypes: true });
    } catch {
      return; // unreadable directory: not our problem
    }
    for (const entry of entries) {
      if (entry.isSymbolicLink()) continue;
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) {
        if (IGNORED_DIRS.has(entry.name)) continue;
        visit(full);
      } else if (entry.isFile() && entry.name === 'AppConfig.kt') {
        hits.push(full);
      }
    }
  })(sourceRoot);

  const rank = (p) => (toPosix(p).includes('/main/') ? 0 : 1);
  return hits.sort((a, b) => rank(a) - rank(b) || a.length - b.length);
}

/* ------------------------------------------------------------------ *
 * Reading the file
 * ------------------------------------------------------------------ */

/** Split `"literal" // note` into its literal and its trailing comment. */
function splitTrailingComment(raw) {
  let inString = false;

  for (let i = 0; i < raw.length; i++) {
    const ch = raw[i];
    if (inString) {
      if (ch === '\\') i++;
      else if (ch === '"') inString = false;
      continue;
    }
    if (ch === '"') inString = true;
    else if (ch === '/' && raw[i + 1] === '/') return [raw.slice(0, i), raw.slice(i)];
  }

  return [raw, ''];
}

/**
 * Parse the constants out of AppConfig.kt.
 *
 * Returns a Map of name -> record, where the record keeps enough of the
 * original line to rebuild it verbatim:
 *
 *   indent + head + <new literal> + gap + comment
 */
function parseConstants(content) {
  const lines = content.split(/\r?\n/);
  const constants = new Map();

  lines.forEach((line, index) => {
    const match = DECLARATION.exec(line);
    if (!match) return;

    const { indent, head, name, type, value } = match.groups;
    const [code, comment] = splitTrailingComment(value);
    const literal = code.trimEnd();

    constants.set(name, {
      name,
      type,
      literal,
      gap: code.slice(literal.length), // whitespace between literal and comment
      comment,
      indent,
      head,
      line: index,
    });
  });

  return { lines, constants };
}

/* ------------------------------------------------------------------ *
 * Value validation
 * ------------------------------------------------------------------ */

/** A Kotlin string literal, fully escaped — including `$`, which starts a template. */
function kotlinString(raw) {
  let out = '';

  for (const ch of raw) {
    const code = ch.codePointAt(0);
    if (ch === '\\') out += '\\\\';
    else if (ch === '"') out += '\\"';
    else if (ch === '$') out += '\\$';
    else if (ch === '\n') out += '\\n';
    else if (ch === '\r') out += '\\r';
    else if (ch === '\t') out += '\\t';
    else if (code < 0x20 || code === 0x7f) {
      throw new BadValue(`contains a control character (U+${code.toString(16).toUpperCase().padStart(4, '0')})`);
    } else out += ch;
  }

  return `"${out}"`;
}

/**
 * Turn a raw string from the command line or JSON into a Kotlin literal.
 * Throws BadValue when the value does not fit the declared type.
 */
function coerceLiteral(name, type, raw) {
  if (!SUPPORTED_TYPES.has(type)) {
    throw new BadValue(`has type ${type}; this script writes Boolean, Int, Long and String only`);
  }

  if (type === 'Boolean') {
    const word = String(raw).trim().toLowerCase();
    if (TRUE_WORDS.has(word)) return 'true';
    if (FALSE_WORDS.has(word)) return 'false';
    throw new BadValue(`expects true or false, got '${raw}'`);
  }

  if (type === 'Int' || type === 'Long') {
    const text = String(raw).trim();
    if (!/^[+-]?\d+$/.test(text)) throw new BadValue(`expects a whole number, got '${raw}'`);

    const value = Number(text);
    if (!Number.isSafeInteger(value)) throw new BadValue(`'${raw}' is too large to be a Kotlin ${type}`);
    if (type === 'Int' && (value < INT_MIN || value > INT_MAX)) {
      throw new BadValue(`${value} is outside the Int range (${INT_MIN}..${INT_MAX}); use Long`);
    }

    // Keep the file's existing style: only add the L when the literal needs it.
    const needsSuffix = type === 'Long' && (value < INT_MIN || value > INT_MAX || /L$/i.test(text));
    return `${value}${needsSuffix ? 'L' : ''}`;
  }

  return kotlinString(String(raw));
}

/**
 * Checks that the declared type cannot express, driven by the constant's name.
 * These are conventions the template already follows, not guesses about intent.
 */
function checkRange(name, type, literal) {
  if (type !== 'Int' && type !== 'Long') return null;

  const value = Number(literal.replace(/L$/i, ''));
  if (/_PERCENT$/.test(name) && (value < 0 || value > 100)) return 'is a percentage, so it must be 0..100';
  if (/_DP$/.test(name) && value < 0) return 'is a dp measurement, so it cannot be negative';
  if (/_MS$/.test(name) && value < 0) return 'is a duration in milliseconds, so it cannot be negative';

  return null;
}

function checkUrl(name, type, literal) {
  if (type !== 'String' || !/_URL$/.test(name)) return null;

  const value = literal.slice(1, -1); // strip the quotes
  if (!/^https?:\/\/[^\s]+$/i.test(value.replace(/\\/g, ''))) {
    return 'must be an http:// or https:// URL';
  }

  return null;
}

/* ------------------------------------------------------------------ *
 * Argument parsing
 * ------------------------------------------------------------------ */

const HELP = `
set-app-config.js — read and change the AppConfig.kt values, in one run.

USAGE
  node tools/set-app-config.js NAME=value [NAME=value ...] [options]

OPTIONS
  --list             Print every constant with its type and current value.
  --project <dir>    Android project root (default: auto-detected).
  --config <file>    AppConfig.kt to edit (default: auto-detected).
  --json <file>      Read values from JSON. Accepts a plain object,
                     {"NAME": value}, the CI's constants array,
                     [{"name","type","value"}, ...], or a whole dispatch
                     payload — its "constants" array is applied and its
                     "webview_url" becomes HOME_URL, exactly as the build
                     workflow maps them. Use - for stdin.
  --force            Skip the name-based checks (ranges and URL shape).
  --dry-run          Report what would change and write nothing.
  --help             Show this help.

WHAT IT CHANGES
  Only the literals. Indentation, the type annotations, spacing, trailing
  comments and line endings are all preserved, and a value that is already
  correct is left alone.

  The type comes from the declaration in AppConfig.kt, so a Boolean refuses
  'maybe' and an Int refuses 'soon' before anything is written.

EXAMPLES
  # the common cases
  node tools/set-app-config.js HOME_URL=https://mysite.com
  node tools/set-app-config.js ENABLE_DOWNLOADS=false ENABLE_FILE_UPLOAD=false
  node tools/set-app-config.js HORIZONTAL_PROGRESS_BAR_HEIGHT_DP=4

  # what can I change?
  node tools/set-app-config.js --list

  # replay what the build worker sent
  node tools/set-app-config.js --json payload.json --dry-run

UNDO
  git diff -- <config>       review what changed
  git checkout -- <config>   put the file back
`;

function parseArgs(argv) {
  const opts = {
    assignments: [],
    jsonFiles: [],
    list: false,
    dryRun: false,
    force: false,
    project: null,
    config: null,
    help: false,
  };

  const need = (flag, value) => {
    if (value === undefined) fail(`${flag} needs a value`);
    return value;
  };

  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];

    if (arg === '--help' || arg === '-h') opts.help = true;
    else if (arg === '--list' || arg === '-l') opts.list = true;
    else if (arg === '--dry-run' || arg === '-n') opts.dryRun = true;
    else if (arg === '--force') opts.force = true;
    else if (arg === '--project') opts.project = need(arg, argv[++i]);
    else if (arg === '--config') opts.config = need(arg, argv[++i]);
    else if (arg === '--json') opts.jsonFiles.push(need(arg, argv[++i]));
    else if (arg.startsWith('--project=')) opts.project = arg.slice('--project='.length);
    else if (arg.startsWith('--config=')) opts.config = arg.slice('--config='.length);
    else if (arg.startsWith('--json=')) opts.jsonFiles.push(arg.slice('--json='.length));
    else if (arg.startsWith('-') && arg !== '-') fail(`unknown option '${arg}'`);
    else {
      const eq = arg.indexOf('=');
      if (eq <= 0) fail(`expected NAME=value, got '${arg}'`);
      opts.assignments.push({ name: arg.slice(0, eq).trim(), value: arg.slice(eq + 1), from: 'command line' });
    }
  }

  return opts;
}

/* ------------------------------------------------------------------ *
 * JSON input
 * ------------------------------------------------------------------ */

function readJsonFile(file) {
  let text;
  try {
    text = file === '-' ? fs.readFileSync(0, 'utf8') : readText(file);
  } catch (err) {
    fail(`cannot read ${file}: ${err.message}`);
  }

  try {
    return JSON.parse(text);
  } catch (err) {
    return fail(`${file}: not valid JSON — ${err.message}`);
  }
}

/** The dispatch field the build worker maps onto HOME_URL. */
const PAYLOAD_URL_FIELD = 'webview_url';

/**
 * Flatten any of the accepted JSON shapes into assignment records.
 * Returns { assignments, ignored }, where `ignored` names the payload fields
 * that belong to other build steps rather than to AppConfig.kt.
 */
function assignmentsFromJson(data, source) {
  const assignments = [];
  const ignored = [];

  const asString = (value, name) => {
    if (value === null || value === undefined) fail(`${source}: ${name} has no value`);
    if (typeof value === 'object') fail(`${source}: ${name} must be a string, number or boolean`);
    return String(value);
  };

  const fromCiArray = (items) => {
    for (const item of items) {
      if (!item || typeof item !== 'object' || Array.isArray(item)) {
        fail(`${source}: every constants entry must be {"name","type","value"}`);
      }
      if (typeof item.name !== 'string' || typeof item.type !== 'string' || !('value' in item)) {
        fail(`${source}: every constants entry must be {"name","type","value"}`);
      }
      assignments.push({
        name: item.name,
        value: asString(item.value, item.name),
        declaredType: item.type,
        from: source,
      });
    }
  };

  if (Array.isArray(data)) {
    fromCiArray(data);
    return { assignments, ignored };
  }

  if (!data || typeof data !== 'object') fail(`${source}: expected a JSON object or array`);

  if (Array.isArray(data.constants)) {
    // A whole dispatch payload. `constants` is the AppConfig part and
    // `webview_url` is the home page — the same mapping the workflow makes.
    // Everything else (app_name, package_name, build_id) is another step's job.
    fromCiArray(data.constants);

    if (data[PAYLOAD_URL_FIELD] !== undefined) {
      assignments.push({
        name: 'HOME_URL',
        value: asString(data[PAYLOAD_URL_FIELD], PAYLOAD_URL_FIELD),
        from: `${source} (${PAYLOAD_URL_FIELD})`,
      });
    }
    for (const name of Object.keys(data)) {
      if (name !== 'constants' && name !== PAYLOAD_URL_FIELD) ignored.push(name);
    }

    return { assignments, ignored };
  }

  for (const [name, value] of Object.entries(data)) {
    assignments.push({ name, value: asString(value, name), from: source });
  }

  return { assignments, ignored };
}

/* ------------------------------------------------------------------ *
 * Suggestions
 * ------------------------------------------------------------------ */

/** Levenshtein distance, iterative and two rows wide. */
function editDistance(a, b) {
  if (a === b) return 0;
  let previous = Array.from({ length: b.length + 1 }, (_, i) => i);

  for (let i = 1; i <= a.length; i++) {
    const current = [i];
    for (let j = 1; j <= b.length; j++) {
      current[j] = Math.min(
        previous[j] + 1,
        current[j - 1] + 1,
        previous[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1),
      );
    }
    previous = current;
  }

  return previous[b.length];
}

/** The closest known names to a typo, or none when nothing is close. */
function suggest(name, names) {
  const upper = name.toUpperCase();
  return names
    .map((candidate) => ({ candidate, distance: editDistance(upper, candidate) }))
    .filter(({ distance }) => distance <= Math.max(2, Math.floor(name.length / 4)))
    .sort((a, b) => a.distance - b.distance)
    .slice(0, 3)
    .map(({ candidate }) => candidate);
}

/* ------------------------------------------------------------------ *
 * Reporting
 * ------------------------------------------------------------------ */

const RULE = '─'.repeat(66);

function pad(text, width) {
  return text + ' '.repeat(Math.max(0, width - text.length));
}

function printValues(config, constants, file) {
  const width = Math.max(...[...constants.keys()].map((n) => n.length));

  log(`\n${bold('App configuration')}`);
  log(dim(RULE));
  log(`  File         ${display(file)}`);
  log(`  Constants    ${constants.size} (${[...constants.values()].filter((c) => SUPPORTED_TYPES.has(c.type)).length} settable)`);
  log(dim(RULE));
  log('');

  for (const constant of constants.values()) {
    const value = SUPPORTED_TYPES.has(constant.type) ? cyan(constant.literal) : dim(constant.literal);
    log(`  ${pad(constant.name, width)}  ${dim(pad(constant.type, 8))} ${value}`);
  }
  log('');
}

/* ------------------------------------------------------------------ *
 * Main
 * ------------------------------------------------------------------ */

function main(argv) {
  const opts = parseArgs(argv);

  if (opts.help) {
    log(HELP.trim());
    return 0;
  }

  /* ---- locate the file ------------------------------------------- */

  const projectDir = opts.project
    ? path.resolve(opts.project)
    : findProjectRoot(process.cwd()) || process.cwd();

  if (opts.project && !fs.existsSync(projectDir)) fail(`no such directory: ${opts.project}`);

  let configPath;
  if (opts.config) {
    configPath = path.resolve(opts.config);
    if (!fs.existsSync(configPath)) fail(`no such file: ${opts.config}`);
  } else {
    const found = findAppConfigFiles(projectDir);
    if (found.length === 0) {
      fail(`no AppConfig.kt under ${display(path.join(projectDir, 'app', 'src'))}\n       Point at one with --config, or at the project with --project.`);
    }

    // The app's own config is the one in the main source set. A copy under
    // test/ or androidTest/ is a fixture and not what a build is configured by.
    const inMain = found.filter((file) => toPosix(file).includes('/main/'));
    const candidates = inMain.length === 1 ? inMain : found;

    if (candidates.length > 1) {
      fail(`found ${candidates.length} AppConfig.kt files; choose one with --config:\n${candidates.map((f) => `         ${display(f)}`).join('\n')}`);
    }
    configPath = candidates[0];
  }

  /* ---- read it ---------------------------------------------------- */

  const original = readText(configPath);
  const { lines, constants } = parseConstants(original);

  if (constants.size === 0) {
    fail(`${display(configPath)} declares no \`const val NAME: Type = value\` constants`);
  }

  const names = [...constants.keys()];

  /* ---- gather the requested changes ------------------------------- */

  const requested = [...opts.assignments];
  for (const file of opts.jsonFiles) {
    const { assignments, ignored } = assignmentsFromJson(readJsonFile(file), file);
    if (ignored.length) {
      log(`${dim('note')}    ${file}: ignoring ${ignored.join(', ')} — not AppConfig values`);
    }
    requested.push(...assignments);
  }

  if (opts.list || requested.length === 0) {
    printValues(projectDir, constants, configPath);
    if (requested.length === 0 && !opts.list) {
      log(`  Change one with:  ${bold(`node ${display(path.join(__dirname, path.basename(__filename)))} NAME=value`)}`);
      log(`  e.g.              HOME_URL=https://mysite.com ENABLE_DOWNLOADS=false\n`);
    }
    return 0;
  }

  /* ---- validate every change before writing anything -------------- */

  const changes = [];
  const problems = [];
  const seen = new Set();

  for (const item of requested) {
    const name = item.name;

    if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(name)) {
      problems.push(`${name}: not a valid constant name`);
      continue;
    }
    if (seen.has(name)) {
      problems.push(`${name}: given more than once`);
      continue;
    }
    seen.add(name);

    const constant = constants.get(name);
    if (!constant) {
      const hint = suggest(name, names);
      problems.push(
        `${name}: no such constant in ${path.basename(configPath)}` +
        (hint.length ? `\n         did you mean ${hint.join(', ')}?` : '') +
        `\n         run with --list to see all ${names.length}`,
      );
      continue;
    }

    if (item.declaredType && item.declaredType !== constant.type) {
      problems.push(
        `${name}: ${item.from} says ${item.declaredType}, AppConfig.kt declares ${constant.type}` +
        '\n         the payload and the template disagree — fix one of them before building',
      );
      continue;
    }

    let literal;
    try {
      literal = coerceLiteral(name, constant.type, item.value);
    } catch (err) {
      if (!(err instanceof BadValue)) throw err;
      problems.push(`${name}: ${err.message}`);
      continue;
    }

    if (!opts.force) {
      const complaint = checkRange(name, constant.type, literal) || checkUrl(name, constant.type, literal);
      if (complaint) {
        problems.push(`${name}: ${literal} ${complaint} (--force to set it anyway)`);
        continue;
      }
    }

    if (literal === constant.literal) continue; // already correct: leave the line alone

    changes.push({ constant, literal });
  }

  if (problems.length) {
    for (const problem of problems) console.error(`${red('error:')} ${problem}`);
    return fail(`${problems.length} value(s) rejected; nothing was written`);
  }

  /* ---- print the plan --------------------------------------------- */

  const width = Math.max(...names.map((n) => n.length));

  log(`\n${bold('App configuration')}`);
  log(dim(RULE));
  log(`  File         ${display(configPath)}`);
  log(dim(RULE));

  if (changes.length === 0) {
    log(`\n  ${green('already set')} — ${requested.length} value(s) checked, none needed changing.\n`);
    return 0;
  }

  log(`\n${bold(`Changes (${changes.length})`)}`);
  for (const { constant, literal } of changes) {
    log(`  ${pad(constant.name, width)}  ${dim(constant.literal)} ${dim('->')} ${green(literal)}`);
  }

  if (opts.dryRun) {
    log(`\n${yellow('dry run')} — nothing written. Run without --dry-run to apply.\n`);
    return 0;
  }

  /* ---- apply ------------------------------------------------------ */

  const updated = [...lines];
  const touched = new Set();

  for (const { constant, literal } of changes) {
    updated[constant.line] = constant.indent + constant.head + literal + constant.gap + constant.comment;
    touched.add(constant.line);
  }

  // Only the intended lines may differ. If the rebuild disturbed anything else,
  // stop before writing rather than commit a mangled file.
  for (let i = 0; i < lines.length; i++) {
    if (!touched.has(i) && lines[i] !== updated[i]) {
      return fail(`internal check failed: line ${i + 1} changed unexpectedly; nothing was written`);
    }
  }

  const eol = original.includes('\r\n') ? '\r\n' : '\n';
  const text = updated.join(eol);
  const trailingNewline = /\r?\n$/.test(original);

  writeText(configPath, trailingNewline ? text : text.replace(/\r?\n$/, ''));

  /* ---- verify ----------------------------------------------------- */

  const after = parseConstants(readText(configPath)).constants;

  for (const { constant, literal } of changes) {
    const now = after.get(constant.name);
    if (!now) return fail(`verification failed: ${constant.name} disappeared from ${display(configPath)}`);
    if (now.literal !== literal) {
      return fail(`verification failed: ${constant.name} is ${now.literal}, expected ${literal}`);
    }
  }

  if (after.size !== constants.size) {
    return fail(`verification failed: the file now declares ${after.size} constants, it had ${constants.size}`);
  }

  log(`\n${dim(RULE)}`);
  log(`${green('Done.')}  ${changes.length} value(s) written to ${display(configPath)}.\n`);
  log(`  Review: ${bold(`git diff -- ${display(configPath)}`)}`);
  log(`  Next:   ${bold('gradlew clean assembleDebug')} to confirm the build.\n`);

  return 0;
}

process.exitCode = main(process.argv.slice(2));
