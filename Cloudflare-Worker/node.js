/**
 * Website APK Builder - Cloudflare Worker Gateway (GitHub Actions Edition)
 * Connects directly to R2 bucket binding: env.BUCKET
 */

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    const path = url.pathname;
    const method = request.method;

    // CORS preflight headers
    const corsHeaders = {
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Methods": "GET, POST, PUT, OPTIONS",
      "Access-Control-Allow-Headers": "Content-Type, Authorization, X-Build-Secret",
    };

    if (method === "OPTIONS") {
      return new Response(null, { headers: corsHeaders });
    }

    try {
      // ----------------------------------------------------
      // 1. DISPATCH BUILD: POST /api/build
      // ----------------------------------------------------
      if (method === "POST" && (path === "/api/build" || path === "/build")) {
        const body = await request.json().catch(() => ({}));
        const targetUrl = body.url || body.target_url;

        if (!targetUrl) {
          return jsonResponse({ error: "Missing required field: 'url'" }, 400, corsHeaders);
        }

        // ---- Security gate --------------------------------------------------
        // Two independent checks, both must pass:
        //   1. A valid, unexpired Firebase ID token in the Authorization header
        //      (proves a real signed-in user; verified against Google's keys).
        //   2. A build document the caller pre-registered in Firestore under
        //      their own uid, created less than BUILD_WINDOW_MS ago (read with a
        //      service account, since the security rules block anonymous reads).
        // Anything else is rejected before a build is ever dispatched.
        const authResult = await authorizeBuild(request, body, env);
        if (authResult.error) {
          return jsonResponse({ error: authResult.error }, authResult.status || 401, corsHeaders);
        }
        const { uid, buildId } = authResult;

        let siteUrl;
        try {
          siteUrl = new URL(String(targetUrl));
        } catch {
          return jsonResponse({ error: `Invalid URL: '${targetUrl}'` }, 400, corsHeaders);
        }
        if (siteUrl.protocol !== "http:" && siteUrl.protocol !== "https:") {
          return jsonResponse(
            { error: `Unsupported URL scheme '${siteUrl.protocol}'. Use http or https.` },
            400, corsHeaders
          );
        }

        const downloadUrl = `${url.origin}/download/${buildId}.apk`;
        // uid rides along so the upload/report handlers can update the correct
        // Firestore doc (users/{uid}/builds/{buildId}) when the build finishes.
        const uidParam = encodeURIComponent(uid);
        const uploadWebhookUrl = `${url.origin}/api/upload/${buildId}?uid=${uidParam}`;
        const statusWebhookUrl = `${url.origin}/api/report/${buildId}?uid=${uidParam}`;

        // Customisation: app name + package name
        const appName = normalizeAppName(body.app_name, siteUrl.hostname);
        const packageName = normalizePackageName(body.package_name)
          || packageNameFromHost(siteUrl.hostname);

        // Customisation: every AppConfig.kt constant
        const resolved = resolveOptions(body);
        if (resolved.error) {
          return jsonResponse({ error: resolved.error }, 400, corsHeaders);
        }

        // Customisation: permissions to strip from the manifest
        const removal = normalizePermissions(body.remove_permissions);
        if (removal.error) {
          return jsonResponse({ error: removal.error }, 400, corsHeaders);
        }

        const constants = Object.entries(resolved.options).map(([key, value]) => ({
          name: OPTION_SCHEMA[key].name,
          type: OPTION_SCHEMA[key].type,
          value
        }));

        // Options to pass down to the Android project via GitHub Actions.
        // NOTE: BUILD_SECRET is deliberately absent - the runner reads it from an
        // encrypted GitHub secret, and this object is echoed back to the caller.
        const buildConfig = {
          BUILD_ID: buildId,
          WEBVIEW_URL: siteUrl.toString(),
          APP_NAME: appName,
          PACKAGE_NAME: packageName,
          CONSTANTS: constants,
          REMOVE_PERMISSIONS: removal.permissions,
          UPLOAD_WEBHOOK_URL: uploadWebhookUrl,
          STATUS_WEBHOOK_URL: statusWebhookUrl
        };

        let ghDispatched = false;
        let ghMessage = "GitHub Actions not configured yet. Build metadata created.";

        // Trigger GitHub Action
        if (env.GITHUB_TOKEN) {
          try {
            await triggerGitHubAction(env, buildConfig);
            ghDispatched = true;
            ghMessage = "GitHub Action triggered successfully.";
          } catch (ghErr) {
            ghMessage = `GitHub dispatch failed: ${ghErr.message}`;
          }
        }

        return jsonResponse({
          success: true,
          build_id: buildId,
          uid,
          status: ghDispatched ? "BUILDING" : "PENDING_GITHUB_SETUP",
          app_name: appName,
          package_name: packageName,
          remove_permissions: removal.permissions,
          download_url: downloadUrl,
          status_url: `${url.origin}/api/status/${buildId}`,
          message: ghMessage,
          options: resolved.options,
          config: buildConfig
        }, 200, corsHeaders);
      }

      // ----------------------------------------------------
      // 2. RECEIVE APK FROM GITHUB ACTIONS: PUT /api/upload/:buildId
      // ----------------------------------------------------
      if (method === "PUT" && path.startsWith("/api/upload/")) {
        const authKey = request.headers.get("X-Build-Secret") || url.searchParams.get("secret");

        // No fallback secret. A default here would be a published password: this
        // worker's source is readable, so an unset BUILD_SECRET must reject every
        // upload rather than accept a known string.
        if (!env.BUILD_SECRET || authKey !== env.BUILD_SECRET) {
          return jsonResponse({ error: "Unauthorized upload" }, 401, corsHeaders);
        }

        let buildId = path.replace("/api/upload/", "").trim();
        if (buildId.endsWith(".apk")) {
          buildId = buildId.replace(".apk", "");
        }

        const objectKey = `apks/${buildId}.apk`;

        // Stream body straight into R2 storage
        const putResult = await env.BUCKET.put(objectKey, request.body, {
          httpMetadata: {
            contentType: "application/vnd.android.package-archive",
            contentDisposition: `attachment; filename="${buildId}.apk"`
          },
          customMetadata: {
            uploadedAt: new Date().toISOString()
          }
        });

        // Flip the Firestore doc to READY so the app's live listener reacts
        // instantly. Best-effort: a failure here must not fail the upload (the
        // APK is already stored and the app can still poll as a fallback).
        const uid = url.searchParams.get("uid");
        if (uid) {
          try {
            await updateBuildDoc(env, uid, buildId, {
              status: "READY",
              sizeBytes: putResult && putResult.size ? putResult.size : 0,
              downloadUrl: `${url.origin}/download/${buildId}.apk`
            });
          } catch (e) {
            console.log(`Firestore READY update failed for ${buildId}: ${e.message}`);
          }
        }

        return jsonResponse({
          success: true,
          message: "APK stored in R2 successfully",
          build_id: buildId,
          object_key: objectKey,
          download_url: `${url.origin}/download/${buildId}.apk`
        }, 200, corsHeaders);
      }

      // ----------------------------------------------------
      // 2b. REPORT BUILD STATUS (e.g. FAILED) FROM GITHUB ACTIONS:
      //     POST /api/report/:buildId?uid=...
      // ----------------------------------------------------
      if (method === "POST" && path.startsWith("/api/report/")) {
        const authKey = request.headers.get("X-Build-Secret") || url.searchParams.get("secret");
        if (!env.BUILD_SECRET || authKey !== env.BUILD_SECRET) {
          return jsonResponse({ error: "Unauthorized report" }, 401, corsHeaders);
        }

        let buildId = path.replace("/api/report/", "").trim();
        if (buildId.endsWith(".apk")) buildId = buildId.replace(".apk", "");

        const uid = url.searchParams.get("uid");
        const reportBody = await request.json().catch(() => ({}));
        // Only a small, known set of statuses may be written this way.
        const allowed = new Set(["FAILED", "READY", "BUILDING"]);
        const status = String(reportBody.status || "").toUpperCase();
        if (!allowed.has(status)) {
          return jsonResponse({ error: "Invalid status" }, 400, corsHeaders);
        }

        if (uid) {
          try {
            await updateBuildDoc(env, uid, buildId, { status });
          } catch (e) {
            return jsonResponse({ error: `Firestore update failed: ${e.message}` }, 502, corsHeaders);
          }
        }

        return jsonResponse({ success: true, build_id: buildId, status }, 200, corsHeaders);
      }

      // ----------------------------------------------------
      // 3. PUBLIC DOWNLOAD: GET /download/:buildId.apk
      // ----------------------------------------------------
      if (method === "GET" && path.startsWith("/download/")) {
        let fileName = path.replace("/download/", "").trim();
        if (!fileName.endsWith(".apk")) {
          fileName += ".apk";
        }
        const objectKey = `apks/${fileName}`;

        const file = await env.BUCKET.get(objectKey);

        if (!file) {
          return new Response(
            `APK '${fileName}' is still compiling or does not exist. Please check /api/status/${fileName.replace('.apk', '')}`,
            { status: 404, headers: { "Content-Type": "text/plain" } }
          );
        }

        const headers = new Headers();
        file.writeHttpMetadata(headers);
        headers.set("Content-Type", "application/vnd.android.package-archive");
        headers.set("Content-Disposition", `attachment; filename="${fileName}"`);
        headers.set("Cache-Control", "public, max-age=3600");

        return new Response(file.body, { headers });
      }

      // ----------------------------------------------------
      // 4. CHECK STATUS: GET /api/status/:buildId
      // ----------------------------------------------------
      if (method === "GET" && path.startsWith("/api/status/")) {
        let buildId = path.replace("/api/status/", "").trim();
        if (buildId.endsWith(".apk")) {
          buildId = buildId.replace(".apk", "");
        }

        const objectKey = `apks/${buildId}.apk`;
        const head = await env.BUCKET.head(objectKey);

        if (head) {
          return jsonResponse({
            build_id: buildId,
            status: "READY",
            size_bytes: head.size,
            uploaded_at: head.uploaded,
            download_url: `${url.origin}/download/${buildId}.apk`
          }, 200, corsHeaders);
        } else {
          return jsonResponse({
            build_id: buildId,
            status: "BUILDING_OR_NOT_FOUND",
            download_url: `${url.origin}/download/${buildId}.apk`
          }, 200, corsHeaders);
        }
      }

      // ----------------------------------------------------
      // 5. LIST BUILD OPTIONS: GET /api/options
      // ----------------------------------------------------
      if (method === "GET" && path === "/api/options") {
        const options = {};
        for (const [key, spec] of Object.entries(OPTION_SCHEMA)) {
          options[key] = {
            kotlin_constant: spec.name,
            type: spec.type,
            default: spec.default,
            ...(spec.min !== undefined ? { min: spec.min, max: spec.max } : {})
          };
        }

        return jsonResponse({
          options,
          aliases: OPTION_ALIASES,
          usage: "POST /api/build with {\"url\": \"https://example.com\", \"options\": { ... }}"
        }, 200, corsHeaders);
      }

      // ----------------------------------------------------
      // 6. DEFAULT HOME / HEALTHCHECK
      // ----------------------------------------------------
      return jsonResponse({
        service: "Website APK Builder API (GitHub Actions Edition)",
        endpoints: {
          trigger_build: "POST /api/build",
          list_options: "GET /api/options",
          upload_apk: "PUT /api/upload/:buildId",
          check_status: "GET /api/status/:buildId",
          download_apk: "GET /download/:buildId.apk"
        }
      }, 200, corsHeaders);

    } catch (err) {
      return jsonResponse({ error: err.message || "Internal Worker Error" }, 500, corsHeaders);
    }
  }
};

// Helper: JSON response wrapper
function jsonResponse(data, status = 200, extraHeaders = {}) {
  return new Response(JSON.stringify(data, null, 2), {
    status,
    headers: {
      "Content-Type": "application/json;charset=UTF-8",
      ...extraHeaders
    }
  });
}

// --------------------------------------------------------
// App name / package name normalisation
// --------------------------------------------------------

// Words that cannot be used as a Java/Kotlin package segment
const RESERVED_WORDS = new Set([
  "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
  "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
  "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
  "interface", "long", "native", "new", "package", "private", "protected", "public",
  "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
  "throw", "throws", "transient", "try", "void", "volatile", "while",
  "as", "fun", "in", "is", "object", "typealias", "val", "var", "when"
]);

/**
 * Validates a caller-supplied package name.
 * Returns the normalised name, or null when the input is unusable
 * (the caller is then given a name derived from the hostname instead).
 */
function normalizePackageName(raw) {
  if (raw === undefined || raw === null || raw === "") return null;
  const pkg = String(raw).trim().toLowerCase();

  // at least two segments, each starting with a letter, letters/digits/underscore only
  if (pkg.length > 100) return null;
  if (!/^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$/.test(pkg)) return null;
  if (pkg.split(".").some((segment) => RESERVED_WORDS.has(segment))) return null;

  return pkg;
}

/** Derives a package name from a hostname: www.example.com -> com.example.webview */
function packageNameFromHost(hostname) {
  const labels = String(hostname)
    .toLowerCase()
    .split(".")
    .filter((label) => label && label !== "www")
    .map((label) => label.replace(/[^a-z0-9_]/g, "_"))
    .map((label) => (/^[0-9]/.test(label) ? `_${label}` : label))
    .map((label) => (RESERVED_WORDS.has(label) ? `${label}_` : label));

  if (labels.length < 2) return "com.bhg.webview";

  const pkg = [...labels.reverse(), "webview"].join(".");
  if (pkg.length > 100) return "com.bhg.webview";

  // Final safety: reject anything the per-label pass could not catch
  if (!/^[a-z_][a-z0-9_]*(\.[a-z_][a-z0-9_]*)+$/.test(pkg)) return "com.bhg.webview";
  if (pkg.split(".").some((s) => RESERVED_WORDS.has(s))) return "com.bhg.webview";

  return pkg;
}

/** Cleans a display name. XML/Android escaping happens in the workflow. */
function normalizeAppName(raw, hostname) {
  // strip C0/C1 control characters and DEL; written as a code-point test so
  // the source file stays plain ASCII
  const isPrintable = (ch) => {
    const code = ch.charCodeAt(0);
    return code > 31 && (code < 127 || code > 159);
  };

  const name = String(raw ?? "")
    .split("")
    .map((ch) => (isPrintable(ch) ? ch : " "))
    .join("")
    .replace(/\s+/g, " ")
    .trim()
    .slice(0, 50);

  if (name) return name;

  const fallback = String(hostname || "").replace(/^www\./, "").trim();
  return fallback ? fallback.slice(0, 50) : "BHG WebView";
}

// --------------------------------------------------------
// Permissions to strip from AndroidManifest.xml
// --------------------------------------------------------

/**
 * Permissions the app cannot work without, so they are never removed.
 *
 * INTERNET           - a WebView with no INTERNET permission cannot load a page.
 * ACCESS_NETWORK_STATE - MainActivity calls ConnectivityManager.getNetworkCapabilities()
 *                        to decide whether to show the offline page; without the
 *                        permission that call throws SecurityException.
 */
const PROTECTED_PERMISSIONS = new Set(["INTERNET", "ACCESS_NETWORK_STATE"]);

/**
 * Normalises the permission names to strip from AndroidManifest.xml.
 *
 * These names are what gets REMOVED, not what is kept. "CAMERA" means the
 * built APK will not ask for the camera.
 *
 * Validation here is structural. tools/set-manifest-permissions.js owns the
 * vocabulary of keywords (CAMERA, LOCATION, STORAGE, ...) and rejects anything
 * it does not recognise, which fails the build before an APK is produced - so
 * a typo costs a build, not a broken app.
 *
 * @returns {{permissions: string[]}|{error: string}}
 */
function normalizePermissions(raw) {
  if (raw === undefined || raw === null || raw === "") return { permissions: [] };

  let list;
  if (Array.isArray(raw)) list = raw;
  else if (typeof raw === "string") list = raw.split(/[\s,]+/);
  else return { error: "'remove_permissions' must be an array of names, or a space-separated string" };

  if (list.length > 40) return { error: "'remove_permissions' accepts at most 40 names" };

  const permissions = [];

  for (const entry of list) {
    if (typeof entry !== "string") return { error: "'remove_permissions' entries must be strings" };

    const name = entry.trim().toUpperCase();
    if (!name) continue;

    // CAMERA, ACCESS_FINE_LOCATION, com.android.vending.BILLING
    if (!/^[A-Z][A-Z0-9_]*(?:\.[A-Za-z0-9_]+)*$/.test(name)) {
      return { error: `Invalid permission name '${entry}'` };
    }
    if (name.length > 120) return { error: `Permission name too long: '${entry}'` };

    // Strip a fully-qualified prefix before the guard, or android.permission.INTERNET
    // would walk straight past it.
    const bare = name.replace(/^ANDROID\.PERMISSION\./, "");
    if (PROTECTED_PERMISSIONS.has(bare)) {
      return { error: `'${bare}' cannot be removed - a WebView app does not work without it` };
    }

    if (!permissions.includes(bare)) permissions.push(bare);
  }

  return { permissions };
}

// --------------------------------------------------------
// Build options -> AppConfig.kt constants
// --------------------------------------------------------

/**
 * Every tunable constant in AppConfig.kt.
 *
 * The key is what API callers send; `name` and `type` are what the workflow
 * writes into the Kotlin source. Adding an option here is the only change
 * needed - the workflow patches whatever it is handed.
 */
const OPTION_SCHEMA = {
  progress_bar:           { name: "SHOW_HORIZONTAL_PROGRESS_BAR",      type: "Boolean", default: true },
  progress_bar_height:    { name: "HORIZONTAL_PROGRESS_BAR_HEIGHT_DP", type: "Int",     default: 3,     min: 0,    max: 20 },
  circular_progress:      { name: "SHOW_CIRCULAR_PROGRESS",            type: "Boolean", default: false },
  circular_progress_size: { name: "CIRCULAR_PROGRESS_SIZE_DP",         type: "Int",     default: 48,    min: 16,   max: 200 },
  swipe_refresh:          { name: "ENABLE_SWIPE_REFRESH",              type: "Boolean", default: true },
  file_upload:            { name: "ENABLE_FILE_UPLOAD",                type: "Boolean", default: true },
  multiple_files:         { name: "ALLOW_MULTIPLE_FILES",              type: "Boolean", default: true },
  downloads:              { name: "ENABLE_DOWNLOADS",                  type: "Boolean", default: true },
  external_links:         { name: "OPEN_EXTERNAL_LINKS",               type: "Boolean", default: true },
  offline_page:           { name: "ENABLE_OFFLINE_PAGE",               type: "Boolean", default: true },
  page_load_timeout_ms:   { name: "PAGE_LOAD_TIMEOUT_MS",              type: "Long",    default: 20000, min: 1000, max: 120000 },
  retry_reveal_percent:   { name: "RETRY_REVEAL_PROGRESS_PERCENT",     type: "Int",     default: 50,    min: 0,    max: 100 },
  fullscreen_video:       { name: "ENABLE_FULLSCREEN_VIDEO",           type: "Boolean", default: true }
};

// Old key names that keep working after a rename
const OPTION_ALIASES = {
  enable_pull_refresh: "swipe_refresh"
};

/**
 * Validates caller-supplied options and fills in defaults.
 *
 * Unknown keys are an error rather than being ignored - a typo'd option that
 * silently does nothing is exactly the failure this pipeline is meant to avoid.
 *
 * @returns {{options: object}|{error: string}}
 */
function resolveOptions(body) {
  const supplied = {
    ...(body.options && typeof body.options === "object" ? body.options : {})
  };

  // top-level keys are accepted as a convenience: {"downloads": false}
  for (const key of Object.keys(OPTION_SCHEMA)) {
    if (supplied[key] === undefined && body[key] !== undefined) supplied[key] = body[key];
  }
  for (const [alias, target] of Object.entries(OPTION_ALIASES)) {
    if (supplied[target] === undefined && body[alias] !== undefined) supplied[target] = body[alias];
  }

  const unknown = Object.keys(supplied).filter((key) => !(key in OPTION_SCHEMA));
  if (unknown.length > 0) {
    return {
      error: `Unknown option(s): ${unknown.join(", ")}. ` +
             `Valid keys: ${Object.keys(OPTION_SCHEMA).join(", ")}`
    };
  }

  const options = {};
  for (const [key, spec] of Object.entries(OPTION_SCHEMA)) {
    let value = supplied[key] === undefined ? spec.default : supplied[key];

    if (spec.type === "Boolean") {
      if (typeof value === "string") value = value.trim().toLowerCase();
      if (value === true || value === "true") value = true;
      else if (value === false || value === "false") value = false;
      else return { error: `Option '${key}' must be true or false` };
    } else {
      const num = typeof value === "number" ? value
        : (typeof value === "string" && value.trim() !== "" ? Number(value) : NaN);
      if (!Number.isFinite(num)) return { error: `Option '${key}' must be a number` };
      if (!Number.isInteger(num)) return { error: `Option '${key}' must be a whole number` };
      if (spec.min !== undefined && num < spec.min) {
        return { error: `Option '${key}' must be >= ${spec.min} (got ${num})` };
      }
      if (spec.max !== undefined && num > spec.max) {
        return { error: `Option '${key}' must be <= ${spec.max} (got ${num})` };
      }
      value = num;
    }

    options[key] = value;
  }

  return { options };
}

// --------------------------------------------------------
// GitHub Actions Workflow Trigger
// --------------------------------------------------------
async function triggerGitHubAction(env, buildConfig) {
  const owner = env.GITHUB_OWNER || "BHG-App-Studios";
  const repo = env.GITHUB_REPO || "WebView";
  const eventType = env.GITHUB_EVENT_TYPE || "build-apk";
  const token = env.GITHUB_TOKEN; // GitHub Personal Access Token

  if (!token) {
    throw new Error("GITHUB_TOKEN is missing in Cloudflare environment variables.");
  }

  // repository_dispatch rather than workflow_dispatch: the payload is free-form,
  // so there are no declared workflow inputs and no manual "Run workflow" form.
  // The workflow file must live on the repository's default branch.
  const url = `https://api.github.com/repos/${owner}/${repo}/dispatches`;

  const response = await fetch(url, {
    method: "POST",
    headers: {
      "Accept": "application/vnd.github.v3+json",
      "Authorization": `Bearer ${token}`,
      "User-Agent": "Cloudflare-Worker",
      "X-GitHub-Api-Version": "2022-11-28"
    },
    body: JSON.stringify({
      event_type: eventType,
      client_payload: {
        build_id: buildConfig.BUILD_ID,
        webview_url: buildConfig.WEBVIEW_URL,
        app_name: buildConfig.APP_NAME,
        package_name: buildConfig.PACKAGE_NAME,
        constants: buildConfig.CONSTANTS,
        remove_permissions: buildConfig.REMOVE_PERMISSIONS,
        upload_webhook_url: buildConfig.UPLOAD_WEBHOOK_URL,
        status_webhook_url: buildConfig.STATUS_WEBHOOK_URL
      }
    })
  });

  if (!response.ok) {
    const errText = await response.text();
    throw new Error(`GitHub API error (${response.status}): ${errText}`);
  }

  return { success: true };
}
// ==========================================================
// SECURITY: Firebase ID-token verification + Firestore provenance check
// ==========================================================
//
// Required Cloudflare environment / secrets for these to work:
//   FIREBASE_PROJECT_ID          - e.g. "website-app-builder-xxxx" (plain var)
//   FIREBASE_SERVICE_ACCOUNT     - the full service-account JSON, as a secret
//                                  (Project settings > Service accounts >
//                                   Generate new private key). Must have
//                                   Firestore read access (Datastore Viewer /
//                                   Editor, or the Firebase Admin role).
//
// If either is unset the gate fails closed: every build is rejected rather
// than silently unprotected.

// How recent the pre-registered Firestore doc must be. A build request is only
// honoured within this window of the doc's createdAt.
const BUILD_WINDOW_MS = 5 * 60 * 1000;         // 5 minutes
const CLOCK_SKEW_MS = 60 * 1000;               // tolerate 1 min of clock drift

// Accepts the app-generated id format: app_<millis>_<shortrandom>
const BUILD_ID_RE = /^app_\d{10,16}_[a-z0-9]{3,12}$/;

// Module-scoped caches. Cloudflare may reuse an isolate across requests, so
// these avoid re-fetching Google's keys / re-minting a token every call.
let _googleKeysCache = { keys: null, expiresAt: 0 };
let _saTokenCache = { token: null, expiresAt: 0 };

/**
 * The full security gate. Returns { uid, buildId } on success, or
 * { error, status } on any failure.
 */
async function authorizeBuild(request, body, env) {
  if (!env.FIREBASE_PROJECT_ID || !env.FIREBASE_SERVICE_ACCOUNT) {
    // Fail closed: misconfiguration must never mean "open to everyone".
    return { error: "Server auth not configured", status: 503 };
  }

  // 1. Bearer token -------------------------------------------------------
  const authHeader = request.headers.get("Authorization") || "";
  const match = authHeader.match(/^Bearer\s+(.+)$/i);
  if (!match) {
    return { error: "Missing Authorization bearer token", status: 401 };
  }
  const idToken = match[1].trim();

  let claims;
  try {
    claims = await verifyFirebaseIdToken(idToken, env.FIREBASE_PROJECT_ID);
  } catch (e) {
    return { error: `Invalid ID token: ${e.message}`, status: 401 };
  }
  const uid = claims.sub;
  if (!uid) return { error: "Token has no subject", status: 401 };

  // 2. build_id shape -----------------------------------------------------
  const buildId = String(body.build_id || body.buildId || "").trim();
  if (!BUILD_ID_RE.test(buildId)) {
    return { error: "Missing or malformed 'build_id'", status: 400 };
  }

  // 3. Provenance: the doc must exist under THIS user and be fresh ---------
  let doc;
  try {
    const accessToken = await getServiceAccountToken(env);
    doc = await fetchBuildDoc(env.FIREBASE_PROJECT_ID, accessToken, uid, buildId);
  } catch (e) {
    return { error: `Authorization check failed: ${e.message}`, status: 502 };
  }

  if (!doc) {
    return { error: "Build was not registered for this account", status: 403 };
  }

  const createdAtMs = doc.createdAtMs;
  if (!Number.isFinite(createdAtMs)) {
    // No server timestamp yet (write not committed) or missing field.
    return { error: "Build registration is incomplete. Try again.", status: 403 };
  }

  const age = Date.now() - createdAtMs;
  if (age > BUILD_WINDOW_MS + CLOCK_SKEW_MS) {
    return { error: "Build request expired. Start a new build.", status: 403 };
  }
  if (age < -CLOCK_SKEW_MS) {
    return { error: "Build timestamp is in the future.", status: 403 };
  }

  return { uid, buildId };
}

// ----------------------------------------------------------
// Firebase ID token verification (RS256, Google public keys)
// ----------------------------------------------------------
async function verifyFirebaseIdToken(token, projectId) {
  const parts = token.split(".");
  if (parts.length !== 3) throw new Error("not a JWT");

  const [headerB64, payloadB64, sigB64] = parts;
  const header = JSON.parse(utf8(base64urlToBytes(headerB64)));
  const payload = JSON.parse(utf8(base64urlToBytes(payloadB64)));

  if (header.alg !== "RS256") throw new Error("unexpected alg");
  if (!header.kid) throw new Error("no kid");

  // Claims
  const now = Math.floor(Date.now() / 1000);
  const skew = 60;
  const issuer = `https://securetoken.google.com/${projectId}`;
  if (payload.aud !== projectId) throw new Error("aud mismatch");
  if (payload.iss !== issuer) throw new Error("iss mismatch");
  if (typeof payload.exp !== "number" || payload.exp < now - skew) throw new Error("expired");
  if (typeof payload.iat !== "number" || payload.iat > now + skew) throw new Error("iat in future");
  if (!payload.sub) throw new Error("no sub");

  // Signature
  const keys = await getGooglePublicKeys();
  const jwk = keys[header.kid];
  if (!jwk) throw new Error("unknown signing key");

  const key = await crypto.subtle.importKey(
    "jwk",
    jwk,
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["verify"]
  );

  const data = new TextEncoder().encode(`${headerB64}.${payloadB64}`);
  const signature = base64urlToBytes(sigB64);
  const ok = await crypto.subtle.verify("RSASSA-PKCS1-v1_5", key, signature, data);
  if (!ok) throw new Error("bad signature");

  return payload;
}

/**
 * Google's Firebase public keys, as a { kid: JWK } map. Cached until the
 * Cache-Control max-age Google returns.
 */
async function getGooglePublicKeys() {
  if (_googleKeysCache.keys && Date.now() < _googleKeysCache.expiresAt) {
    return _googleKeysCache.keys;
  }

  // The JWK Set endpoint returns keys directly in JWK form - no X.509 parsing.
  const res = await fetch(
    "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com"
  );
  if (!res.ok) throw new Error(`key fetch failed (${res.status})`);
  const body = await res.json();

  const map = {};
  for (const jwk of body.keys || []) {
    if (jwk.kid) map[jwk.kid] = jwk;
  }

  // Respect the endpoint's cache lifetime, default 1 hour.
  let ttl = 3600;
  const cc = res.headers.get("Cache-Control") || "";
  const m = cc.match(/max-age=(\d+)/);
  if (m) ttl = parseInt(m[1], 10);
  _googleKeysCache = { keys: map, expiresAt: Date.now() + ttl * 1000 };

  return map;
}

// ----------------------------------------------------------
// Service-account OAuth token (for Firestore REST reads)
// ----------------------------------------------------------
async function getServiceAccountToken(env) {
  if (_saTokenCache.token && Date.now() < _saTokenCache.expiresAt) {
    return _saTokenCache.token;
  }

  const sa = JSON.parse(env.FIREBASE_SERVICE_ACCOUNT);
  const now = Math.floor(Date.now() / 1000);

  const jwtHeader = { alg: "RS256", typ: "JWT" };
  const jwtClaim = {
    iss: sa.client_email,
    scope: "https://www.googleapis.com/auth/datastore",
    aud: "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600
  };

  const unsigned =
    `${bytesToBase64url(new TextEncoder().encode(JSON.stringify(jwtHeader)))}.` +
    `${bytesToBase64url(new TextEncoder().encode(JSON.stringify(jwtClaim)))}`;

  const key = await importPkcs8(sa.private_key);
  const sigBuf = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    key,
    new TextEncoder().encode(unsigned)
  );
  const assertion = `${unsigned}.${bytesToBase64url(new Uint8Array(sigBuf))}`;

  const res = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion
    })
  });
  if (!res.ok) {
    const t = await res.text();
    throw new Error(`token exchange failed (${res.status}): ${t}`);
  }
  const data = await res.json();

  // Refresh a little before actual expiry.
  _saTokenCache = {
    token: data.access_token,
    expiresAt: Date.now() + (data.expires_in - 60) * 1000
  };
  return data.access_token;
}

/**
 * Reads users/{uid}/builds/{buildId} via the Firestore REST API.
 * Returns { createdAtMs } or null when the document does not exist.
 */
async function fetchBuildDoc(projectId, accessToken, uid, buildId) {
  const name = `projects/${projectId}/databases/(default)/documents/users/${encodeURIComponent(uid)}/builds/${encodeURIComponent(buildId)}`;
  const res = await fetch(`https://firestore.googleapis.com/v1/${name}`, {
    headers: { Authorization: `Bearer ${accessToken}` }
  });

  if (res.status === 404) return null;
  if (!res.ok) throw new Error(`firestore read failed (${res.status})`);

  const doc = await res.json();
  const fields = doc.fields || {};

  const created = fields.createdAt && fields.createdAt.timestampValue;
  const createdAtMs = created ? Date.parse(created) : NaN;

  return { createdAtMs };
}

/**
 * Patches selected fields on users/{uid}/builds/{buildId} via the Firestore
 * REST API, using the service account. Only the given fields are touched
 * (updateMask), so it never disturbs the config the app wrote. Always stamps
 * updatedAt.
 */
async function updateBuildDoc(env, uid, buildId, changes) {
  const accessToken = await getServiceAccountToken(env);
  const name = `projects/${env.FIREBASE_PROJECT_ID}/databases/(default)/documents/users/${encodeURIComponent(uid)}/builds/${encodeURIComponent(buildId)}`;

  const all = { ...changes, updatedAt: new Date().toISOString() };
  const fields = {};
  const maskFields = [];

  for (const [key, value] of Object.entries(all)) {
    fields[key] = toFirestoreValue(key, value);
    maskFields.push(key);
  }

  const params = maskFields.map((f) => `updateMask.fieldPaths=${encodeURIComponent(f)}`).join("&");
  const res = await fetch(`https://firestore.googleapis.com/v1/${name}?${params}`, {
    method: "PATCH",
    headers: {
      Authorization: `Bearer ${accessToken}`,
      "Content-Type": "application/json"
    },
    body: JSON.stringify({ fields })
  });

  if (!res.ok) {
    const t = await res.text();
    throw new Error(`firestore patch failed (${res.status}): ${t}`);
  }
}

/** Maps a JS value to a typed Firestore REST value. updatedAt -> timestamp. */
function toFirestoreValue(key, value) {
  if (key === "updatedAt" && typeof value === "string") {
    return { timestampValue: value };
  }
  if (typeof value === "boolean") return { booleanValue: value };
  if (typeof value === "number") {
    return Number.isInteger(value)
      ? { integerValue: String(value) }
      : { doubleValue: value };
  }
  return { stringValue: String(value) };
}

// ----------------------------------------------------------
// Encoding helpers (WebCrypto-friendly)
// ----------------------------------------------------------
function base64urlToBytes(b64url) {
  const b64 = b64url.replace(/-/g, "+").replace(/_/g, "/") +
    "=".repeat((4 - (b64url.length % 4)) % 4);
  const bin = atob(b64);
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  return bytes;
}

function bytesToBase64url(bytes) {
  let bin = "";
  for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function utf8(bytes) {
  return new TextDecoder().decode(bytes);
}

/** Imports a PEM PKCS#8 private key (from the service-account JSON) for signing. */
async function importPkcs8(pem) {
  const body = pem
    .replace(/-----BEGIN PRIVATE KEY-----/, "")
    .replace(/-----END PRIVATE KEY-----/, "")
    .replace(/\s+/g, "");
  const der = base64urlToBytes(body.replace(/\+/g, "-").replace(/\//g, "_"));
  return crypto.subtle.importKey(
    "pkcs8",
    der,
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"]
  );
}
