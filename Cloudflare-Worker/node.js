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

        const timestamp = Date.now();
        const randomId = Math.random().toString(36).substring(2, 7);
        const buildId = `app_${timestamp}_${randomId}`;
        const downloadUrl = `${url.origin}/download/${buildId}.apk`;
        const uploadWebhookUrl = `${url.origin}/api/upload/${buildId}`;

        // Customisation: app name + package name
        const appName = normalizeAppName(body.app_name, siteUrl.hostname);
        const packageName = normalizePackageName(body.package_name)
          || packageNameFromHost(siteUrl.hostname);

        // Customisation: every AppConfig.kt constant
        const resolved = resolveOptions(body);
        if (resolved.error) {
          return jsonResponse({ error: resolved.error }, 400, corsHeaders);
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
          UPLOAD_WEBHOOK_URL: uploadWebhookUrl
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
          status: ghDispatched ? "BUILDING" : "PENDING_GITHUB_SETUP",
          app_name: appName,
          package_name: packageName,
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
        const expectedSecret = env.BUILD_SECRET || "bhg_apk_secret_key";

        if (authKey !== expectedSecret) {
          return jsonResponse({ error: "Unauthorized upload" }, 401, corsHeaders);
        }

        let buildId = path.replace("/api/upload/", "").trim();
        if (buildId.endsWith(".apk")) {
          buildId = buildId.replace(".apk", "");
        }

        const objectKey = `apks/${buildId}.apk`;

        // Stream body straight into R2 storage
        await env.BUCKET.put(objectKey, request.body, {
          httpMetadata: {
            contentType: "application/vnd.android.package-archive",
            contentDisposition: `attachment; filename="${buildId}.apk"`
          },
          customMetadata: {
            uploadedAt: new Date().toISOString()
          }
        });

        return jsonResponse({
          success: true,
          message: "APK stored in R2 successfully",
          build_id: buildId,
          object_key: objectKey,
          download_url: `${url.origin}/download/${buildId}.apk`
        }, 200, corsHeaders);
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
    .map((label) => (/^[0-9]/.test(label) ? `_${label}` : label));

  if (labels.length < 2) return "com.bhg.webview";

  const pkg = [...labels.reverse(), "webview"].join(".");
  return pkg.length <= 100 ? pkg : "com.bhg.webview";
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
        upload_webhook_url: buildConfig.UPLOAD_WEBHOOK_URL
      }
    })
  });

  if (!response.ok) {
    const errText = await response.text();
    throw new Error(`GitHub API error (${response.status}): ${errText}`);
  }

  return { success: true };
}