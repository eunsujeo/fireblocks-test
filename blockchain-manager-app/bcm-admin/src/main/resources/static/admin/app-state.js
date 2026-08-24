const FILTER_KEYS = ["q", "chainId", "adopted", "testnet", "network", "symbol"];

export function adminRouteFromPath(pathname) {
  const path = pathname.replace(/\/$/, "");
  if (path.includes("/test-runs/")) return "testRun";
  if (path.endsWith("/test-runs")) return "testRuns";
  if (path.includes("/change-requests/")) return "changeRequest";
  if (path.includes("/transactions/")) return "transaction";
  if (path.endsWith("/band-s")) return "bandS";
  if (path.endsWith("/emergency")) return "emergency";
  if (path.endsWith("/contracts")) return "contracts";
  if (path.endsWith("/policies")) return "policies";
  if (path.endsWith("/networks")) return "networks";
  if (path.endsWith("/assets")) return "assets";
  if (path.endsWith("/search")) return "search";
  return "dashboard";
}

export function filtersFromUrl(url) {
  return Object.fromEntries(
    FILTER_KEYS
      .map((key) => [key, url.searchParams.get(key)])
      .filter(([, value]) => value !== null && value !== ""),
  );
}

export function filtersToUrl(path, filters) {
  const params = new URLSearchParams();
  FILTER_KEYS.forEach((key) => {
    const value = filters[key];
    if (value !== undefined && value !== null && value !== "") params.set(key, String(value));
  });
  const query = params.toString();
  return query ? `${path}?${query}` : path;
}

export function assetDiscoverySymbol(value) {
  const normalized = String(value || "").trim().toUpperCase();
  return /^[A-Z0-9_]{1,16}$/.test(normalized) && !/^0X[0-9A-F]+$/.test(normalized) ? normalized : null;
}

export function registeredAssetMapping(candidate, mappings) {
  return mappings.find((mapping) => mapping.network === candidate.network && (
    candidate.contractAddress
      ? mapping.contractAddress?.toLowerCase() === candidate.contractAddress.toLowerCase()
      : !mapping.contractAddress
  ));
}

export function resolveViewState({ loading = false, status = 200, error = false, state = "FRESH", data = null }) {
  if (loading) return "loading";
  if (status === 403) return "forbidden";
  if (error) return "error";
  if (state === "STALE") return "stale";
  if (state === "PARTIAL") return "partial";
  if (Array.isArray(data) && data.length === 0) return "empty";
  return "completed";
}

export function isGlobalSearchShortcut(event) {
  const tagName = event.target?.tagName?.toUpperCase();
  const editing = ["INPUT", "TEXTAREA", "SELECT"].includes(tagName) || event.target?.isContentEditable;
  return event.key === "/" && !event.ctrlKey && !event.metaKey && !event.altKey && !editing;
}

export function formatAdminTime(value, formatLocal = (date) => date.toLocaleString("ko-KR", { hour12: false })) {
  if (!value) return null;
  const date = new Date(value);
  return {
    local: Number.isNaN(date.getTime()) ? value : formatLocal(date),
    utc: value,
  };
}

export function formatCoreTime(value, formatLocal = (date) => date.toLocaleString("ko-KR", { hour12: false })) {
  if (!value) return null;
  if (!/^\d{14}$/.test(value)) return formatAdminTime(value, formatLocal);
  const iso = `${value.slice(0, 4)}-${value.slice(4, 6)}-${value.slice(6, 8)}T${value.slice(8, 10)}:${value.slice(10, 12)}:${value.slice(12, 14)}Z`;
  return formatAdminTime(iso, formatLocal);
}

export async function runSingleFlight(control, action) {
  if (control.disabled) return false;
  control.disabled = true;
  control.setAttribute?.("aria-busy", "true");
  try {
    await action();
    return true;
  } finally {
    control.disabled = false;
    control.removeAttribute?.("aria-busy");
  }
}

export function transactionIdentifierFromPath(pathname) {
  const match = pathname.match(/\/admin\/transactions\/([^/]+)\/?$/);
  if (!match) return null;
  try {
    return decodeURIComponent(match[1]);
  } catch {
    return null;
  }
}

export function changeRequestIdFromPath(pathname) {
  const match = pathname.match(/\/admin\/change-requests\/([^/]+)\/?$/);
  if (!match) return null;
  try {
    return decodeURIComponent(match[1]);
  } catch {
    return null;
  }
}

export function testRunIdFromPath(pathname) {
  const match = pathname.match(/\/admin\/test-runs\/([^/]+)\/?$/);
  if (!match) return null;
  try {
    return decodeURIComponent(match[1]);
  } catch {
    return null;
  }
}

export function shouldRefreshTestRun(state) {
  return state === "PENDING" || state === "RUNNING";
}
