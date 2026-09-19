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

        const timestamp = Date.now();
        const randomId = Math.random().toString(36).substring(2, 7);
        const buildId = `app_${timestamp}_${randomId}`;
        const downloadUrl = `${url.origin}/download/${buildId}.apk`;
        const uploadWebhookUrl = `${url.origin}/api/upload/${buildId}`;

        // Options to pass down to Android AppConfig.kt via GitHub Actions
        const buildConfig = {
          BUILD_ID: buildId,
          WEBVIEW_URL: targetUrl,
          APP_NAME: body.app_name || "BHG WebView",
          ENABLE_SPLASH: body.enable_splash !== undefined ? String(body.enable_splash) : "true",
          ENABLE_PULL_REFRESH: body.enable_pull_refresh !== undefined ? String(body.enable_pull_refresh) : "false",
          ENABLE_ZOOM: body.enable_zoom !== undefined ? String(body.enable_zoom) : "true",
          UPLOAD_WEBHOOK_URL: uploadWebhookUrl,
          BUILD_SECRET: env.BUILD_SECRET || "bhg_apk_secret_key"
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
          download_url: downloadUrl,
          status_url: `${url.origin}/api/status/${buildId}`,
          message: ghMessage,
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
      // 5. DEFAULT HOME / HEALTHCHECK
      // ----------------------------------------------------
      return jsonResponse({
        service: "Website APK Builder API (GitHub Actions Edition)",
        endpoints: {
          trigger_build: "POST /api/build",
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
// GitHub Actions Workflow Trigger
// --------------------------------------------------------
async function triggerGitHubAction(env, buildConfig) {
  const owner = env.GITHUB_OWNER || "BHG-App-Studios"; 
  const repo = env.GITHUB_REPO || "WebView"; 
  const ref = env.GITHUB_BRANCH || "server"; // Triggers the run on your 'server' branch
  const workflowId = env.GITHUB_WORKFLOW_ID || "build-apk.yml";
  const token = env.GITHUB_TOKEN; // GitHub Personal Access Token

  if (!token) {
    throw new Error("GITHUB_TOKEN is missing in Cloudflare environment variables.");
  }

  const url = `https://api.github.com/repos/${owner}/${repo}/actions/workflows/${workflowId}/dispatches`;

  const response = await fetch(url, {
    method: "POST",
    headers: {
      "Accept": "application/vnd.github.v3+json",
      "Authorization": `Bearer ${token}`,
      "User-Agent": "Cloudflare-Worker",
      "X-GitHub-Api-Version": "2022-11-28"
    },
    body: JSON.stringify({
      ref: ref, 
      inputs: {
        build_id: buildConfig.BUILD_ID,
        webview_url: buildConfig.WEBVIEW_URL,
        enable_splash: String(buildConfig.ENABLE_SPLASH),
        enable_pull_refresh: String(buildConfig.ENABLE_PULL_REFRESH),
        enable_zoom: String(buildConfig.ENABLE_ZOOM),
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