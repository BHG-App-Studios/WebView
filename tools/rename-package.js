#!/usr/bin/env node
/**
 * rename-package.js — rename an Android app's package, fully, in one run.
 *
 * A package rename is not a single find-and-replace. This script does all of it:
 *
 *   1. namespace + applicationId in every module's build.gradle / build.gradle.kts
 *   2. the source directory trees  app/src/<set>/java|kotlin/com/old/pkg  ->  .../new/pkg
 *   3. `package` declarations, `import` statements and any fully-qualified class
 *      name in .kt / .java / .xml / .pro / .gradle files
 *   4. string literals that hold the package (instrumentation tests assert it)
 *   5. files you point at with --include (CI workflows that hardcode the old path)
 *
 * It writes as soon as you run it. Pass --dry-run to see the plan instead.
 * Undo is git's job:  git diff  to review,  git checkout . + git clean -fd  to revert.
 *
 * Usage:
 *   node tools/rename-package.js com.acme.shop
 *   node tools/rename-package.js com.acme.shop --include ../.github/workflows/build-apk.yml
 *   node tools/rename-package.js com.acme.shop --dry-run
 *
 * Run with --help for the full option list. No dependencies; Node 18+.
 */

'use strict';

const fs = require('node:fs');
const path = require('node:path');
const process = require('node:process');

const SCRIPT_PATH = __filename;
const SCRIPT_DIR = __dirname;

/* ------------------------------------------------------------------ *
 * Constants
 * ------------------------------------------------------------------ */

/** Directories never walked: VCS, caches and build output. */
const IGNORED_DIRS = new Set([
  '.git', '.hg', '.svn',
  '.gradle', '.idea', '.kotlin', '.vs', '.vscode',
  'build', 'out', 'node_modules', 'captures', '.cxx',
]);

/** Extensions worth rewriting. A whitelist, so binaries are never corrupted. */
const TEXT_EXTENSIONS = new Set([
  '.kt', '.kts', '.java', '.gradle', '.groovy',
  '.xml', '.pro', '.properties', '.json', '.toml', '.cfg', '.ini',
  '.yml', '.yaml', '.md', '.txt',
  '.js', '.mjs', '.cjs', '.ts', '.html', '.htm',
  '.sh', '.bat', '.cmd', '.ps1',
]);

const JAVA_RESERVED = new Set([
  'abstract', 'assert', 'boolean', 'break', 'byte', 'case', 'catch', 'char',
  'class', 'const', 'continue', 'default', 'do', 'double', 'else', 'enum',
  'extends', 'final', 'finally', 'float', 'for', 'goto', 'if', 'implements',
  'import', 'instanceof', 'int', 'interface', 'long', 'native', 'new',
  'package', 'private', 'protected', 'public', 'return', 'short', 'static',
  'strictfp', 'super', 'switch', 'synchronized', 'this', 'throw', 'throws',
  'transient', 'try', 'void', 'volatile', 'while',
  'true', 'false', 'null', '_',
  // Kotlin hard keywords (cannot appear as identifiers in Kotlin source)
  'as', 'fun', 'in', 'is', 'object', 'typealias', 'val', 'var', 'when',
]);

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
const rel = (from, to) => path.relative(from, to).split(path.sep).join('/');

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

/** True when `child` is `parent` or lives beneath it. Path-segment aware. */
function isInside(child, parent) {
  const from = path.resolve(parent);
  const to = path.resolve(child);
  if (to === from) return true;
  const relative = path.relative(from, to);
  return relative !== '' && !relative.startsWith('..') && !path.isAbsolute(relative);
}

function escapeRegExp(s) {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/** A path for humans: relative when it stays inside the working directory. */
function display(p) {
  const relative = path.relative(process.cwd(), p);
  if (relative === '' || relative.startsWith('..') || path.isAbsolute(relative)) return p;
  return toPosix(relative);
}

/** Recursively collect file and directory paths, honouring IGNORED_DIRS. */
function walk(root) {
  const files = [];
  const dirs = [];

  (function visit(dir) {
    let entries;
    try {
      entries = fs.readdirSync(dir, { withFileTypes: true });
    } catch {
      return; // unreadable directory: not our problem, skip it
    }
    for (const entry of entries) {
      if (entry.isSymbolicLink()) continue; // never follow links out of the tree
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) {
        if (IGNORED_DIRS.has(entry.name)) continue;
        dirs.push(full);
        visit(full);
      } else if (entry.isFile()) {
        files.push(full);
      }
    }
  })(root);

  return { files, dirs };
}

function isTextFile(file) {
  if (!TEXT_EXTENSIONS.has(path.extname(file).toLowerCase())) return false;
  let fd;
  try {
    fd = fs.openSync(file, 'r');
    const buffer = Buffer.alloc(8000);
    const read = fs.readSync(fd, buffer, 0, buffer.length, 0);
    return !buffer.subarray(0, read).includes(0); // NUL byte => binary
  } catch {
    return false;
  } finally {
    if (fd !== undefined) fs.closeSync(fd);
  }
}

function readText(file) {
  return fs.readFileSync(file, 'utf8');
}

function writeText(file, text, mode) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, text, mode === undefined ? 'utf8' : { encoding: 'utf8', mode });
}

/* ------------------------------------------------------------------ *
 * Package-name validation
 * ------------------------------------------------------------------ */

function validatePackageName(pkg) {
  const errors = [];
  const warnings = [];

  if (!/^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$/.test(pkg)) {
    errors.push(
      `"${pkg}" is not a valid Android package name. It needs at least two ` +
      'dot-separated segments; each segment must start with a letter and may ' +
      'contain only letters, digits and underscores.',
    );
    return { errors, warnings };
  }

  const segments = pkg.split('.');
  for (const segment of segments) {
    if (JAVA_RESERVED.has(segment)) {
      errors.push(`segment "${segment}" is a Java keyword and cannot be used in a package name`);
    }
  }

  if (segments[0] === 'java' || segments[0] === 'javax') {
    errors.push(`packages starting with "${segments[0]}." cannot be loaded by the Android runtime`);
  }
  if (segments[0] === 'android') {
    warnings.push('a package starting with "android." can collide with the framework');
  }
  if (pkg.length > 200) {
    errors.push('package name is longer than 200 characters');
  }
  if (pkg !== pkg.toLowerCase()) {
    warnings.push(
      'the name contains uppercase letters. Android accepts them, but the ' +
      'convention is all-lowercase and some stores reject new uppercase ids.',
    );
  }

  return { errors, warnings };
}

/* ------------------------------------------------------------------ *
 * Gradle parsing
 * ------------------------------------------------------------------ */

const NAMESPACE_RE = /(^|[^\w.])namespace\s*=?\s*(['"])([^'"]+)\2/;
const APPLICATION_ID_RE = /(^|[^\w.])applicationId\s*=?\s*(['"])([^'"]+)\2/;

/**
 * Set one quoted gradle property, preserving the surrounding syntax (`=` vs a
 * bare keyword, quote style, indentation) and leaving modules whose value is
 * something else entirely alone.
 */
function setGradleValue(content, keyword, expected, next) {
  const regex = new RegExp(`(^|[^\\w.])(${keyword})(\\s*=?\\s*)(['"])([^'"]+)\\4`, 'm');
  return content.replace(regex, (match, prefix, name, separator, quote, value) =>
    value === expected ? `${prefix}${name}${separator}${quote}${next}${quote}` : match);
}

function findGradleModules(projectDir, allFiles) {
  const modules = [];
  for (const file of allFiles) {
    if (path.basename(file) !== 'build.gradle' && path.basename(file) !== 'build.gradle.kts') {
      continue;
    }
    const content = readText(file);
    const ns = content.match(NAMESPACE_RE);
    const id = content.match(APPLICATION_ID_RE);
    if (ns || id) {
      modules.push({
        gradleFile: file,
        dir: path.dirname(file),
        namespace: ns ? ns[3] : null,
        applicationId: id ? id[3] : null,
      });
    }
  }
  return modules;
}

/* ------------------------------------------------------------------ *
 * Replacement engine
 *
 * Two forms of the same package appear in a project: the dotted form in code
 * (`com.BHG.webview`, `import com.BHG.webview.databinding.X`) and the path form
 * in filesystem references (`app/src/main/java/com/BHG/webview/AppConfig.kt`).
 *
 * The lookbehind/lookahead keep the match on a package boundary: `notcom.x.y`
 * and `com.x.yExtra` are left alone, a URL like `https://foo.com/BHG/webview`
 * is left alone (preceded by a dot), while a genuine subpackage
 * (`com.BHG.webview.ui`) is rewritten too.
 * ------------------------------------------------------------------ */

function buildReplacer(pairs, separator) {
  if (pairs.length === 0) return (text) => text;

  // Longest first, so `com.a.b` wins over `com.a` in a single leftmost pass.
  const sorted = [...pairs].sort((a, b) => b.from.length - a.from.length);
  const lookup = new Map();

  for (const { from, to } of sorted) {
    const key = from.split('.').join(separator);
    if (!lookup.has(key)) lookup.set(key, to.split('.').join(separator));
  }

  const alternatives = [...lookup.keys()].map(escapeRegExp).join('|');
  const regex = new RegExp(
    `(?<![A-Za-z0-9_$.])(${alternatives})(?![A-Za-z0-9_$])`,
    'g',
  );

  return (text) => text.replace(regex, (match) => lookup.get(match) ?? match);
}

function buildReplacers(pairs) {
  return [
    { label: 'dotted', apply: buildReplacer(pairs, '.') },
    { label: 'path', apply: buildReplacer(pairs, '/') },
  ];
}

/**
 * Detects a *complete* reference to one of the old packages.
 *
 * Stricter than the replacer: a match followed by another path segment counts
 * as part of a longer package, not as a leftover. That matters when the new
 * name extends the old one — after renaming com.a.b to com.a.b.c, every
 * surviving mention of "com.a.b" is really the prefix of "com.a.b.c".
 */
function buildStragglerDetector(pairs) {
  const forms = new Set();
  for (const { from } of pairs) {
    forms.add(from);
    forms.add(from.split('.').join('/'));
  }
  if (forms.size === 0) return () => false;

  const alternatives = [...forms]
    .sort((a, b) => b.length - a.length)
    .map(escapeRegExp)
    .join('|');

  // Not preceded by an identifier/dot, not followed by an identifier, and not
  // followed by another package segment.
  const regex = new RegExp(
    `(?<![A-Za-z0-9_$.])(?:${alternatives})(?![A-Za-z0-9_$])(?![./][A-Za-z0-9_$])`,
  );

  return (text) => regex.test(text);
}

/* ------------------------------------------------------------------ *
 * Planning
 * ------------------------------------------------------------------ */

/** Every `<module>/src/<sourceSet>/<java|kotlin>/<old/pkg>` that must be moved. */
function planDirectoryMoves(dirs, oldPathSegments, newPathSegments, merge) {
  const sourceRoots = dirs.filter((d) => {
    const name = path.basename(d);
    if (name !== 'java' && name !== 'kotlin') return false;
    return path.basename(path.dirname(path.dirname(d))) === 'src';
  });

  const moves = [];
  for (const root of sourceRoots) {
    const from = path.join(root, ...oldPathSegments);
    if (!fs.existsSync(from) || !fs.statSync(from).isDirectory()) continue;

    const to = path.join(root, ...newPathSegments);
    if (path.resolve(from) === path.resolve(to)) continue;

    // Merging into a package that already has code in it is almost always a
    // mistake: the two trees can declare different packages. Ask first.
    if (fs.existsSync(to) && !merge) {
      const existing = walk(to).files;
      if (existing.length > 0) {
        fail(
          `the target package directory already exists and is not empty:\n` +
          `    ${to}\n` +
          `  It contains ${existing.length} file(s), for example ` +
          `${path.basename(existing[0])}.\n` +
          `  Move or delete it first, or pass --merge to combine the two packages.`,
        );
      }
    }

    const { files } = walk(from);
    const entries = files.map((file) => ({
      from: file,
      to: path.join(to, path.relative(from, file)),
    }));

    for (const entry of entries) {
      // The `to` path is brand new, so this only trips on a genuine collision
      // (e.g. both the old and the new package already exist side by side).
      if (fs.existsSync(entry.to)) {
        fail(
          `refusing to overwrite an existing file:\n` +
          `    ${entry.to}\n` +
          `  The new package already exists in this source tree. Rename or remove ` +
          `the target directory first: ${to}`,
        );
      }
    }

    moves.push({ root, from, to, entries });
  }

  return moves;
}

function planContentEdits(files, replacers, transforms = new Map()) {
  const edits = [];

  for (const file of files) {
    // This script names the old package in its own documentation; leave it be.
    if (path.resolve(file) === path.resolve(SCRIPT_PATH)) continue;
    if (!isTextFile(file)) continue;

    let before;
    try {
      before = readText(file);
    } catch {
      continue;
    }

    let after = before;
    for (const replacer of replacers) after = replacer.apply(after);

    // Per-file finishing pass. The gradle files use it to pin namespace and
    // applicationId to their exact targets: when both properties hold the same
    // literal but must end up different, no text substitution can decide alone.
    const transform = transforms.get(path.resolve(file));
    if (transform) after = transform(after);

    if (after !== before) edits.push({ file, before, after });
  }

  return edits;
}

/** Changed lines between two versions of the same file, for the preview. */
function diffLines(before, after) {
  const a = before.split('\n');
  const b = after.split('\n');
  const changed = [];
  const max = Math.max(a.length, b.length);

  for (let i = 0; i < max; i++) {
    if (a[i] !== b[i]) {
      changed.push({ line: i + 1, before: a[i] ?? '', after: b[i] ?? '' });
    }
  }
  return changed;
}

/* ------------------------------------------------------------------ *
 * Verification
 * ------------------------------------------------------------------ */

function verify({ projectDir, files, oldPackage, isStraggler, newPackage, newApplicationId, modules, moves }) {
  const problems = [];
  const notes = [];

  // 1. no trace of the old package anywhere in scope
  const stragglers = [];
  for (const file of files) {
    // This script documents the old package by name; that is not a straggler.
    if (path.resolve(file) === path.resolve(SCRIPT_PATH)) continue;
    if (!isTextFile(file)) continue;
    let content;
    try {
      content = readText(file);
    } catch {
      continue;
    }
    if (isStraggler(content)) stragglers.push(rel(projectDir, file));
  }
  if (stragglers.length > 0) {
    problems.push(`old package still present in:\n    ${stragglers.join('\n    ')}`);
  }

  // 2. every Kotlin/Java file declares the package its directory implies
  for (const move of moves) {
    for (const entry of move.entries) {
      const ext = path.extname(entry.to).toLowerCase();
      if (ext !== '.kt' && ext !== '.java') continue;
      if (!fs.existsSync(entry.to)) {
        problems.push(`${rel(projectDir, entry.to)} is missing after the move`);
        continue;
      }

      const content = readText(entry.to);
      const declared = content.match(/^\s*package\s+([\w.]+)/m);
      if (!declared) {
        notes.push(`${rel(projectDir, entry.to)} has no package declaration`);
        continue;
      }
      const expected = toPosix(path.relative(move.root, path.dirname(entry.to)))
        .split('/')
        .join('.');
      if (declared[1] !== expected) {
        problems.push(
          `${rel(projectDir, entry.to)} declares "${declared[1]}" but sits in "${expected}"`,
        );
      }
    }
  }

  // 3. no abandoned old-package directory left behind in a source tree
  const oldSegments = oldPackage.split('.');
  const newSegments = newPackage.split('.');
  for (const move of moves) {
    const leftover = path.join(move.root, ...oldSegments);
    if (!fs.existsSync(leftover)) continue;

    // Renaming into your own namespace (com.a.b -> com.a.b.c) keeps the old
    // directory on purpose: it is now an ancestor of the new package.
    const newDir = path.join(move.root, ...newSegments);
    if (isInside(newDir, leftover)) continue;

    const remaining = walk(leftover).files;
    problems.push(
      `${rel(projectDir, leftover)} still exists after the move` +
      (remaining.length > 0
        ? ` and holds ${remaining.length} file(s) the rename did not carry over:\n      ` +
          remaining.map((f) => rel(projectDir, f)).join('\n      ')
        : ''),
    );
  }

  // 4. gradle agrees
  for (const module of modules) {
    const content = readText(module.gradleFile);
    const ns = content.match(NAMESPACE_RE);
    const id = content.match(APPLICATION_ID_RE);
    if (ns && ns[3] !== newPackage) {
      problems.push(`${rel(projectDir, module.gradleFile)} namespace is "${ns[3]}", expected "${newPackage}"`);
    }
    if (id && id[3] !== newApplicationId) {
      problems.push(`${rel(projectDir, module.gradleFile)} applicationId is "${id[3]}", expected "${newApplicationId}"`);
    }
  }

  return { problems, notes };
}

/* ------------------------------------------------------------------ *
 * Argument parsing
 * ------------------------------------------------------------------ */

const HELP = `
${bold('rename-package.js')} — rename an Android app's package, fully, in one run.

${bold('USAGE')}
  node tools/rename-package.js <new.package.name> [options]

${bold('OPTIONS')}
  --project <dir>    Android project root (default: auto-detected)
  --app-id <id>      Set applicationId to something other than the new package.
  --include <path>   Also rewrite this file or directory (repeatable). Use it for
                     files outside the project, such as CI workflows that hardcode
                     the old package path.
  --merge            Allow the move to merge into a package directory that
                     already holds code. Refused by default.
  --dry-run          Report what would change and write nothing.
  --help             Show this help.

${bold('WHAT IT CHANGES')}
  namespace and applicationId in build.gradle / build.gradle.kts
  app/src/<set>/java|kotlin/<old/pkg>  ->  .../<new/pkg>
  package declarations, imports and fully-qualified names in source and XML
  string literals that hold the package name
  anything passed via --include

  It checks its own work afterwards: the old package must be gone, every Kotlin
  or Java file must declare the package its directory implies, and the gradle
  files must agree. A failed check is reported and exits non-zero.

${bold('EXAMPLES')}
  # rename and keep the CI workflow path in sync
  node tools/rename-package.js com.acme.shop \\
      --include ../.github/workflows/build-apk.yml

  # look before you leap
  node tools/rename-package.js com.acme.shop --dry-run

  # app id deliberately different from the namespace
  node tools/rename-package.js com.acme.shop --app-id com.acme.shop.pro

${bold('UNDO')}
  git diff          review what changed
  git checkout .    put every tracked file back
  git clean -fd     drop directories the rename created
`;

function parseArgs(argv) {
  const opts = {
    positional: [],
    dryRun: false,
    merge: false,
    include: [],
    project: null,
    appId: null,
    help: false,
  };

  const needsValue = new Set(['--project', '--app-id', '--include']);

  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];

    if (arg === '--help' || arg === '-h') { opts.help = true; continue; }
    if (arg === '--dry-run' || arg === '-n') { opts.dryRun = true; continue; }
    if (arg === '--merge') { opts.merge = true; continue; }

    if (needsValue.has(arg)) {
      const value = argv[++i];
      if (value === undefined) fail(`${arg} needs a value`);
      if (arg === '--project') opts.project = value;
      else if (arg === '--app-id') opts.appId = value;
      else opts.include.push(value);
      continue;
    }

    if (arg.startsWith('-')) fail(`unknown option "${arg}" (try --help)`);
    opts.positional.push(arg);
  }

  return opts;
}

/* ------------------------------------------------------------------ *
 * Project discovery
 * ------------------------------------------------------------------ */

function looksLikeProject(dir) {
  if (!dir || !fs.existsSync(dir) || !fs.statSync(dir).isDirectory()) return false;
  return ['settings.gradle', 'settings.gradle.kts', 'build.gradle', 'build.gradle.kts']
    .some((name) => fs.existsSync(path.join(dir, name)));
}

function resolveProjectDir(explicit) {
  if (explicit) {
    const resolved = path.resolve(explicit);
    if (!looksLikeProject(resolved)) {
      fail(`"${resolved}" does not look like an Android project (no settings.gradle / build.gradle)`);
    }
    return resolved;
  }

  const candidates = [process.cwd(), path.dirname(SCRIPT_DIR)];
  for (const candidate of candidates) {
    if (looksLikeProject(candidate)) return candidate;
  }

  const children = fs.readdirSync(process.cwd(), { withFileTypes: true })
    .filter((e) => e.isDirectory() && !IGNORED_DIRS.has(e.name))
    .map((e) => path.join(process.cwd(), e.name))
    .filter(looksLikeProject);

  if (children.length === 1) return children[0];
  if (children.length > 1) {
    fail(
      `several Android projects are here — pick one with --project:\n    ` +
      children.map((c) => display(c)).join('\n    '),
    );
  }

  fail('could not find an Android project; pass one with --project <dir>');
}

/* ------------------------------------------------------------------ *
 * Main
 * ------------------------------------------------------------------ */

function main() {
  const opts = parseArgs(process.argv.slice(2));

  if (opts.help) {
    log(HELP);
    return;
  }

  const newPackage = (opts.positional[0] ?? '').trim();
  if (!newPackage) {
    log(HELP);
    fail('no new package name given');
  }
  if (opts.positional.length > 1) {
    fail(`unexpected extra argument "${opts.positional[1]}"`);
  }

  const { errors, warnings } = validatePackageName(newPackage);
  if (errors.length > 0) fail(errors.join('\n  '));
  for (const warning of warnings) warn(warning);

  /* ---- discover ------------------------------------------------- */

  const projectDir = resolveProjectDir(opts.project);
  const { files, dirs } = walk(projectDir);
  const modules = findGradleModules(projectDir, files);

  if (modules.length === 0) {
    fail(`no build.gradle with a namespace or applicationId found under ${projectDir}`);
  }

  const oldPackage = modules.find((m) => m.namespace)?.namespace
    ?? modules.find((m) => m.applicationId)?.applicationId;

  if (!oldPackage) fail('could not determine the current package name');

  const oldApplicationId = modules.find((m) => m.applicationId)?.applicationId ?? null;
  const newApplicationId = opts.appId ?? newPackage;

  if (oldPackage === newPackage && (oldApplicationId ?? oldPackage) === newApplicationId) {
    fail(`the app is already named "${newPackage}"`);
  }

  // The pairs fed to the replacer. applicationId is usually the same string as
  // the namespace, in which case one pair covers both; it only needs its own
  // pair when the two genuinely differ.
  const pairs = [];
  if (oldPackage !== newPackage) pairs.push({ from: oldPackage, to: newPackage });
  if (oldApplicationId && oldApplicationId !== oldPackage && oldApplicationId !== newApplicationId) {
    pairs.push({ from: oldApplicationId, to: newApplicationId });
  }

  const replacers = buildReplacers(pairs);
  const isStraggler = buildStragglerDetector(pairs);

  // Pin namespace and applicationId in each module to their exact target
  // values. This runs after the generic rewrite, so the value it looks for is
  // the one the replacers leave behind, not the one on disk now.
  const afterReplacers = (value) => replacers.reduce((text, r) => r.apply(text), value);

  const gradleTransforms = new Map();
  for (const module of modules) {
    const namespaceToFix = module.namespace === oldPackage && module.namespace !== newPackage
      ? afterReplacers(module.namespace)
      : null;
    const appIdToFix = module.applicationId
      && module.applicationId === (oldApplicationId ?? oldPackage)
      && module.applicationId !== newApplicationId
      ? afterReplacers(module.applicationId)
      : null;

    if (!namespaceToFix && !appIdToFix) continue;

    gradleTransforms.set(path.resolve(module.gradleFile), (text) => {
      let out = text;
      if (namespaceToFix) out = setGradleValue(out, 'namespace', namespaceToFix, newPackage);
      if (appIdToFix) out = setGradleValue(out, 'applicationId', appIdToFix, newApplicationId);
      return out;
    });
  }

  if (newApplicationId !== newPackage) {
    warn(
      `applicationId "${newApplicationId}" differs from the package "${newPackage}". ` +
      'Only the gradle applicationId is decoupled — every other file, including ' +
      'instrumented tests that assert the package name, follows the package.',
    );
  }

  /* ---- files outside the project (--include) --------------------- */

  const externalFiles = [];
  for (const include of opts.include) {
    const resolved = path.resolve(include);
    if (!fs.existsSync(resolved)) fail(`--include path does not exist: ${resolved}`);

    if (fs.statSync(resolved).isDirectory()) {
      const { files: nested } = walk(resolved);
      externalFiles.push(...nested);
    } else {
      externalFiles.push(resolved);
    }
  }

  /* ---- plan ------------------------------------------------------ */

  const moves = planDirectoryMoves(
    dirs,
    oldPackage.split('.'),
    newPackage.split('.'),
    opts.merge,
  );

  const projectEdits = planContentEdits(files, replacers, gradleTransforms);
  const includeEdits = planContentEdits(externalFiles, replacers);
  const allEdits = [...projectEdits, ...includeEdits];

  // Files that the move itself relocates: their rewrite happens at the new path.
  const movedFrom = new Set(moves.flatMap((m) => m.entries.map((e) => path.resolve(e.from))));

  /* ---- report the plan ------------------------------------------- */

  const line = '─'.repeat(66);
  log(`\n${bold('Android package rename')}`);
  log(line);
  log(`  Project      ${dim(projectDir)}`);
  log(`  Modules      ${modules.map((m) => rel(projectDir, m.dir) || '.').join(', ')}`);
  log(`  Old package  ${yellow(oldPackage)}`);
  log(`  New package  ${green(newPackage)}`);
  if (newApplicationId !== newPackage) {
    log(`  New app id   ${green(newApplicationId)}`);
  } else {
    log(`  New app id   ${dim('(same as package)')}`);
  }
  log(line);

  if (moves.length > 0) {
    log(`\n${bold('Source directories')} ${dim(`(${moves.length})`)}`);
    for (const move of moves) {
      log(`  ${rel(projectDir, move.from)}`);
      log(`    -> ${green(rel(projectDir, move.to))}   ${dim(`${move.entries.length} file(s)`)}`);
    }
  } else {
    log(`\n${dim('Source directories: none to move')}`);
  }

  if (allEdits.length > 0) {
    log(`\n${bold('Files to rewrite')} ${dim(`(${allEdits.length})`)}`);
    for (const edit of allEdits) {
      const isExternal = !isInside(edit.file, projectDir);
      const shown = isExternal ? edit.file : rel(projectDir, edit.file);
      log(`  ${shown}${isExternal ? dim('  (external)') : ''}`);
      const changed = diffLines(edit.before, edit.after);
      for (const change of changed.slice(0, 4)) {
        log(`    ${dim(String(change.line).padStart(4))} ${red(`- ${change.before.trim()}`)}`);
        log(`         ${green(`+ ${change.after.trim()}`)}`);
      }
      if (changed.length > 4) log(dim(`         … ${changed.length - 4} more line(s)`));
    }
  } else {
    log(`\n${dim('Files to rewrite: none')}`);
  }

  /* ---- out-of-scope references ----------------------------------- */

  // Files elsewhere in the repository that still name the old package. They are
  // not touched, but they are exactly the ones that break a build later.
  const outside = new Set();
  try {
    const gitRoot = findGitRoot(projectDir);
    if (gitRoot) {
      const { files: repoFiles } = walk(gitRoot);
      for (const file of repoFiles) {
        if (isInside(file, projectDir)) continue;
        if (!isTextFile(file)) continue;
        let content;
        try {
          content = readText(file);
        } catch {
          continue;
        }
        if (isStraggler(content)) {
          outside.add(rel(gitRoot, file));
        }
      }
    }
  } catch {
    /* a scan failure must never block the rename */
  }

  if (outside.size > 0) {
    log(`\n${bold(yellow('References outside the project'))} ${dim('(not modified)')}`);
    for (const file of outside) log(`  ${file}`);
    log(
      dim(`  Add the ones that matter with:  --include <path>\n`) +
      dim(`  A CI workflow that hardcodes the old source path will fail without it.`),
    );
  }

  /* ---- dry run stops here ---------------------------------------- */

  if (opts.dryRun) {
    log(`\n${bold('DRY RUN')} — nothing was modified.`);
    log(`Run without ${cyan('--dry-run')} to make these changes.\n`);
    process.exitCode = 0;
    return;
  }

  if (moves.length === 0 && allEdits.length === 0) {
    log(`\nNothing to do.\n`);
    return;
  }

  /* ---- apply ----------------------------------------------------- */

  log(`\n${bold('Applying')}`);

  // 1. Move the source trees. File by file, so that renaming a package into or
  //    out of its own parent (com.a -> com.a.b and the reverse) still works.
  for (const move of moves) {
    for (const entry of move.entries) {
      fs.mkdirSync(path.dirname(entry.to), { recursive: true });
      try {
        fs.renameSync(entry.from, entry.to);
      } catch (err) {
        if (err.code !== 'EXDEV') throw err;
        fs.copyFileSync(entry.from, entry.to); // different volumes
        fs.unlinkSync(entry.from);
      }
      log(`  ${green('moved')}    ${rel(projectDir, entry.from)} ${dim('->')} ${rel(projectDir, entry.to)}`);
    }

    // Drop the directories the move emptied, stopping at the source root.
    // `move.from` is the old package directory itself, so the walk starts there.
    const stop = path.resolve(move.root);
    let dir = move.from;
    let isLeaf = true;
    while (path.resolve(dir) !== stop && isInside(dir, stop)) {
      try {
        fs.rmdirSync(dir);
      } catch {
        // Something the move did not carry over lives here. Say so instead of
        // leaving a silent surprise in the source tree.
        if (isLeaf) {
          log(`  ${yellow('note')}    ${rel(projectDir, dir)} is not empty and was left in place:`);
          for (const name of fs.readdirSync(dir)) log(dim(`            ${name}`));
        }
        break;
      }
      isLeaf = false;
      dir = path.dirname(dir);
    }
  }

  // 2. Rewrite contents. Files that were moved are written at their new path;
  //    everything else in place. Modes are preserved from the file on disk.
  const rewritten = new Map(); // path -> text to write

  for (const edit of allEdits) {
    const from = path.resolve(edit.file);
    // A moved file's edit was planned against its old path.
    const target = movedFrom.has(from)
      ? moves.flatMap((m) => m.entries).find((e) => path.resolve(e.from) === from).to
      : edit.file;
    rewritten.set(target, edit.after);
  }

  for (const [target, text] of rewritten) {
    writeText(target, text, fs.statSync(target).mode);
    log(`  ${green('rewrote')}  ${isInside(target, projectDir) ? rel(projectDir, target) : target}`);
  }

  /* ---- verify ---------------------------------------------------- */

  const { files: freshFiles } = walk(projectDir);
  const { problems, notes } = verify({
    projectDir,
    files: [...freshFiles, ...externalFiles],
    oldPackage,
    isStraggler,
    newPackage,
    newApplicationId,
    modules,
    moves,
  });

  log('');
  log(line);

  if (problems.length > 0) {
    log(red(bold('VERIFICATION FAILED')));
    for (const problem of problems) log(`  ${red('•')} ${problem}`);
    log(`\n  The rename was applied. Review it with ${cyan('git diff')} and revert with ` +
        `${cyan('git checkout . && git clean -fd')}.\n`);
    process.exitCode = 1;
    return;
  }

  log(green(bold('Done.')));
  for (const note of notes) log(`  ${dim('note:')} ${note}`);
  log(
    `\n  ${moves.reduce((n, m) => n + m.entries.length, 0)} file(s) moved, ` +
    `${allEdits.length} file(s) rewritten.`,
  );
  log(`  Review: ${cyan('git diff')}   Undo: ${cyan('git checkout . && git clean -fd')}`);
  log(`\n  Next: ${cyan('gradlew clean assembleDebug')} to confirm the build.\n`);
}

/** Nearest ancestor containing a .git entry, if any. */
function findGitRoot(start) {
  let dir = path.resolve(start);
  for (;;) {
    if (fs.existsSync(path.join(dir, '.git'))) return dir;
    const parent = path.dirname(dir);
    if (parent === dir) return null;
    dir = parent;
  }
}

main();
