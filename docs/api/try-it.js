(function (root) {
  "use strict";

  function firstMediaExample(media, sample) {
    if (!media) return undefined;
    if (Object.prototype.hasOwnProperty.call(media, "example")) return media.example;
    const examples = media.examples || {};
    const first = Object.values(examples)[0];
    if (first && Object.prototype.hasOwnProperty.call(first, "value")) return first.value;
    return sample(media.schema || {});
  }

  function inferBaseUrl(location) {
    if (!location || !/^https?:$/.test(location.protocol || "")) return "";
    const path = String(location.pathname || "");
    const markerIndex = path.indexOf("/api-docs");
    const prefix = markerIndex >= 0 ? path.slice(0, markerIndex) : "";
    return String(location.origin || "").replace(/\/$/, "") + prefix.replace(/\/$/, "");
  }

  function validBaseUrl(value) {
    try {
      const parsed = new URL(value);
      return /^https?:$/.test(parsed.protocol) && !parsed.username && !parsed.password;
    } catch (_) {
      return false;
    }
  }

  function localRecoveryCommands(target) {
    try {
      const hostname = new URL(target).hostname.toLowerCase();
      if (!["127.0.0.1", "localhost", "[::1]", "::1"].includes(hostname)) return [];
      return [
        "./scripts/local.sh status",
        "./scripts/local.sh down && ./scripts/local.sh up stub",
        "./scripts/local.sh down && ./scripts/local.sh up fireblocks",
      ];
    } catch (_) {
      return [];
    }
  }

  function requestTarget(baseUrl, path, parameters) {
    let resolvedPath = path;
    const query = new URLSearchParams();
    (parameters || []).forEach((parameter) => {
      const value = parameter.value;
      if (value === undefined || value === null || value === "") return;
      if (parameter.in === "path") {
        resolvedPath = resolvedPath.replace("{" + parameter.name + "}", encodeURIComponent(String(value)));
      } else if (parameter.in === "query") {
        query.append(parameter.name, String(value));
      }
    });
    if (/\{[^}]+\}/.test(resolvedPath)) throw new Error("필수 path parameter를 입력하세요.");
    const suffix = query.toString();
    return baseUrl.replace(/\/$/, "") + resolvedPath + (suffix ? "?" + suffix : "");
  }

  function shellQuote(value) {
    return "'" + String(value).replace(/'/g, "'\"'\"'") + "'";
  }

  function curlCommand(method, target, headers, body) {
    const parts = ["curl -sS", "-X " + String(method).toUpperCase(), shellQuote(target)];
    Object.entries(headers || {}).forEach(([name, value]) => {
      parts.push("-H " + shellQuote(name + ": " + value));
    });
    if (body !== undefined) parts.push("--data-raw " + shellQuote(JSON.stringify(body)));
    return parts.join(" \\\n  ");
  }

  function responseBody(text, contentType) {
    const raw = String(text == null ? "" : text);
    if (!raw) return { kind: "empty", formatted: "(응답 본문 없음)", raw };
    const type = String(contentType || "").toLowerCase();
    const mayBeJson = type.includes("json") || /^[\s]*[\[{]/.test(raw);
    if (mayBeJson) {
      try {
        return { kind: "json", formatted: JSON.stringify(JSON.parse(raw), null, 2), raw };
      } catch (_) {
        // Content-Type과 실제 응답이 다르면 원문을 그대로 보여 준다.
      }
    }
    return { kind: "text", formatted: raw, raw };
  }

  function categorySlug(value) {
    return String(value || "")
      .trim()
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, "-")
      .replace(/^-|-$/g, "") || "api";
  }

  function categoryRoute(hash, allowed, fallback) {
    let raw = String(hash || "").replace(/^#/, "");
    try { raw = decodeURIComponent(raw); } catch (_) { return { category: fallback, anchor: "" }; }
    const parts = raw.split("/");
    const category = allowed.includes(parts[0]) ? parts[0] : fallback;
    return { category, anchor: category === parts[0] ? parts.slice(1).join("/") : "" };
  }

  root.BCM_API_TRY = {
    firstMediaExample,
    inferBaseUrl,
    validBaseUrl,
    localRecoveryCommands,
    requestTarget,
    curlCommand,
    responseBody,
    categorySlug,
    categoryRoute,
  };
})(typeof window === "undefined" ? globalThis : window);
