#!/usr/bin/env node
/**
 * set-app-name.js — change app_name in res/values/strings.xml.
 *
 *   node tools/set-app-name.js "Acme Shop"
 *   node tools/set-app-name.js                     # show the current name
 *
 * The launcher label comes from <string name="app_name">, which the manifest
 * picks up as @string/app_name, so this is the one place the shown app name
 * lives. Only that literal is rewritten; the rest of the file is untouched.
 *
 * It writes as soon as you run it. git is the undo:
 *   git diff -- <file>          review
 *   git checkout -- <file>      put it back
 *
 * No dependencies. Node 18+.
 */

'use strict';

const fs = require('node:fs');
const path = require('node:path');

/** <string name="app_name">VALUE</string>, however it is spaced. */
const APP_NAME = /(<string\s+name\s*=\s*"app_name"\s*>)([\s\S]*?)(<\/string>)/;

/** Android caps a launcher label at 50 characters. */
const MAX_LENGTH = 50;

/* ------------------------------------------------------------------ *
 * Escaping
 * ------------------------------------------------------------------ */

/**
 * Make a plain name safe to put in a string resource, the same way the build
 * workflow does it — a name that survives here must survive CI unchanged.
 *
 * Android string resources need the backslash escapes; XML text needs the
 * entity ones. Backslash goes first, or it would double the ones added after.
 */
function escapeName(raw) {
  let name = raw.replace(/[\x00-\x1f\x7f]/g, ' ').trim().slice(0, MAX_LENGTH);

  name = name.replace(/\\/g, '\\\\').replace(/'/g, "\\'").replace(/"/g, '\\"');

  // A leading @ or ? would be read as a resource or attribute reference.
  if (name[0] === '@' || name[0] === '?') name = '\\' + name;

  return name.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

/* ------------------------------------------------------------------ *
 * Finding strings.xml
 * ------------------------------------------------------------------ */

/** Walk up from the working directory to the project's default strings.xml. */
function findStrings(start) {
  let dir = path.resolve(start);
  for (;;) {
    const candidate = path.join(dir, 'app', 'src', 'main', 'res', 'values', 'strings.xml');
    if (fs.existsSync(candidate)) return candidate;
    const parent = path.dirname(dir);
    if (parent === dir) return null;
    dir = parent;
  }
}

/* ------------------------------------------------------------------ *
 * Main
 * ------------------------------------------------------------------ */

const HELP = `
set-app-name.js — change app_name in res/values/strings.xml.

USAGE
  node tools/set-app-name.js "Acme Shop"
  node tools/set-app-name.js                    show the current name

OPTIONS
  --file <path>   Edit this strings.xml instead of finding one.
  --dry-run       Show the change, write nothing.
  --help          This text.

NOTES
  The launcher label comes from <string name="app_name">, which the manifest
  reads as @string/app_name. This changes only that literal.

  Names are escaped the way the build workflow escapes them, so a name that
  works here works in CI: apostrophes, quotes and backslashes get Android's
  escapes, & < > get XML entities, and a leading @ or ? is neutralised.
  Longer than ${MAX_LENGTH} characters is trimmed to ${MAX_LENGTH}.

EXAMPLES
  node tools/set-app-name.js "Acme Shop"
  node tools/set-app-name.js "Bob's Burgers"     -> Bob\\'s Burgers
  node tools/set-app-name.js "AT&T Wireless"     -> AT&amp;T Wireless
`;

function main(argv) {
  let fileArg = null;
  let dryRun = false;
  const words = [];

  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === '--help' || arg === '-h') { console.log(HELP.trim()); return 0; }
    else if (arg === '--dry-run') dryRun = true;
    else if (arg === '--file') fileArg = argv[++i];
    else words.push(arg);
  }

  const file = fileArg ? path.resolve(fileArg) : findStrings(process.cwd());
  if (!file || !fs.existsSync(file)) {
    const tried = fileArg ?? path.join(process.cwd(), 'app', 'src', 'main', 'res', 'values', 'strings.xml');
    return fail(`no strings.xml at ${tried}\n       run this from inside the project, or pass --file <path>`);
  }

  const relative = path.relative(process.cwd(), file);
  const shown = relative && !relative.startsWith('..') ? relative : file;

  const original = fs.readFileSync(file, 'utf8');
  const found = APP_NAME.exec(original);
  if (!found) {
    return fail(`${shown} has no <string name="app_name"> element`);
  }

  const [, open, current, close] = found;

  if (words.length === 0) {
    console.log(`\n${shown}`);
    console.log(`  app_name = ${current}\n`);
    console.log(`Change it:  node tools/set-app-name.js "Acme Shop"\n`);
    return 0;
  }

  const name = words.join(' ').trim();
  if (!name) return fail('the new name is empty');

  const next = escapeName(name);
  if (!next) return fail('the new name is empty once trimmed to a valid label');

  if (next === current) {
    console.log(`\n${shown}`);
    console.log(`  app_name is already "${current}" — nothing to do\n`);
    return 0;
  }

  console.log(`\n${shown}`);
  console.log(`  ${dim('app_name')}  ${current}  ->  ${next}`);
  if (next !== name) console.log(`  ${dim(`escaped from "${name}" for the string resource`)}`);

  if (dryRun) {
    console.log(`\n  dry run — nothing written\n`);
    return 0;
  }

  // Rebuilt by slicing rather than replacing, so a name containing $ or & is
  // inserted literally instead of being read as a replacement pattern.
  const updated = original.slice(0, found.index) + open + next + close
    + original.slice(found.index + found[0].length);
  fs.writeFileSync(file, updated, 'utf8');

  const check = APP_NAME.exec(fs.readFileSync(file, 'utf8'));
  if (!check || check[2] !== next) {
    return fail(`wrote ${shown} but app_name did not change — check it with: git diff -- ${shown}`);
  }

  console.log(`\n  Undo:  git checkout -- ${shown}\n`);
  return 0;
}

const dim = (s) => (process.stdout.isTTY && !process.env.NO_COLOR ? `\x1b[2m${s}\x1b[0m` : s);

function fail(message) {
  console.error(`\nerror: ${message}\n`);
  return 1;
}

process.exitCode = main(process.argv.slice(2));
