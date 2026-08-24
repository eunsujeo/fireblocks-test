#!/usr/bin/env python3
"""Generate the small read-only BCM Admin client surface from the repository OpenAPI."""

from __future__ import annotations

import argparse
from pathlib import Path

import yaml

PROJECT = Path(__file__).resolve().parents[1]
ROOT = Path(__file__).resolve().parents[3]
SPEC_PATH = ROOT / "docs/api/openapi.yaml"
OUTPUT = PROJECT / "src/main/kotlin/com/whatto/bcm/admin/client/generated/BcmAdminApiTypes.kt"
SCHEMAS = (
    "Network",
    "AssetMapping",
    "AssetCandidate",
    "AssetCatalogSource",
    "AssetCandidateSearchResult",
    "Meta",
    "NetworkListResponse",
    "NetworkResponse",
    "AssetMappingListResponse",
    "AssetCandidateListResponse",
    "AssetMappingResponse",
    "AdminTransactionInvestigationSummary",
    "AdminTransactionTimelineEntry",
    "AdminTransactionBoost",
    "AdminTransactionSweepItem",
    "AdminTransactionSweepExecution",
    "AdminTransactionAllowance",
    "AdminTransactionFeeQuote",
    "AdminTransactionInvestigation",
    "AdminTransactionInvestigationResponse",
    "AdminVault",
    "AdminVaultListResponse",
    "AdminContract",
    "AdminContractListResponse",
    "AdminPolicy",
    "AdminPolicyListResponse",
    "AdminBandSItem",
    "AdminBandS",
    "AdminBandSListResponse",
    "AdminExecutionGate",
    "AdminExternalControlEvidence",
    "AdminAllowanceRevocationItem",
    "AdminAllowanceRevocation",
    "AdminWebhookRecovery",
    "AdminExecutionGateResume",
    "AdminExecutionGateOverview",
    "AdminExecutionGateOverviewResponse",
    "AdminWebhookRuntime",
    "AdminSweepRuntime",
    "AdminRuntimeReadiness",
    "AdminRuntimeReadinessResponse",
    "AdminChangeDecision",
    "AdminChangeRequest",
    "AdminChangeRequestResponse",
)
CLASS_NAMES = {
    "Network": "AdminNetwork",
    "AssetMapping": "AdminAssetMapping",
    "AssetCandidate": "AdminAssetCandidate",
    "AssetCatalogSource": "AdminAssetCatalogSource",
    "AssetCandidateSearchResult": "AdminAssetCandidateSearchResult",
    "Meta": "BcmMeta",
    "NetworkListResponse": "BcmNetworkListResponse",
    "NetworkResponse": "BcmNetworkResponse",
    "AssetMappingListResponse": "BcmAssetMappingListResponse",
    "AssetCandidateListResponse": "BcmAssetCandidateListResponse",
    "AssetMappingResponse": "BcmAssetMappingResponse",
    "AdminTransactionInvestigationSummary": "AdminTransactionInvestigationSummary",
    "AdminTransactionTimelineEntry": "AdminTransactionTimelineEntry",
    "AdminTransactionBoost": "AdminTransactionBoost",
    "AdminTransactionSweepItem": "AdminTransactionSweepItem",
    "AdminTransactionSweepExecution": "AdminTransactionSweepExecution",
    "AdminTransactionAllowance": "AdminTransactionAllowance",
    "AdminTransactionFeeQuote": "AdminTransactionFeeQuote",
    "AdminTransactionInvestigation": "AdminTransactionInvestigation",
    "AdminTransactionInvestigationResponse": "BcmAdminTransactionInvestigationResponse",
    "AdminVault": "AdminVault",
    "AdminVaultListResponse": "BcmAdminVaultListResponse",
    "AdminContract": "AdminContract",
    "AdminContractListResponse": "BcmAdminContractListResponse",
    "AdminPolicy": "AdminPolicy",
    "AdminPolicyListResponse": "BcmAdminPolicyListResponse",
    "AdminBandSItem": "AdminBandSItem",
    "AdminBandS": "AdminBandS",
    "AdminBandSListResponse": "BcmAdminBandSListResponse",
    "AdminExecutionGate": "AdminExecutionGate",
    "AdminExternalControlEvidence": "AdminExternalControlEvidence",
    "AdminAllowanceRevocationItem": "AdminAllowanceRevocationItem",
    "AdminAllowanceRevocation": "AdminAllowanceRevocation",
    "AdminWebhookRecovery": "AdminWebhookRecovery",
    "AdminExecutionGateResume": "AdminExecutionGateResume",
    "AdminExecutionGateOverview": "AdminExecutionGateOverview",
    "AdminExecutionGateOverviewResponse": "BcmAdminExecutionGateOverviewResponse",
    "AdminWebhookRuntime": "AdminWebhookRuntime",
    "AdminSweepRuntime": "AdminSweepRuntime",
    "AdminRuntimeReadiness": "AdminRuntimeReadiness",
    "AdminRuntimeReadinessResponse": "BcmAdminRuntimeReadinessResponse",
    "AdminChangeDecision": "AdminChangeDecision",
    "AdminChangeRequest": "AdminChangeRequest",
    "AdminChangeRequestResponse": "BcmAdminChangeRequestResponse",
}


def kotlin_type(schema: dict) -> tuple[str, bool]:
    if "$ref" in schema:
        name = schema["$ref"].rsplit("/", 1)[-1]
        return CLASS_NAMES.get(name, f"Bcm{name}"), False
    if "oneOf" in schema:
        concrete = next(item for item in schema["oneOf"] if item.get("type") != "null")
        result, _ = kotlin_type(concrete)
        return result, True
    kind = schema.get("type")
    if kind == "array":
        item_type, _ = kotlin_type(schema["items"])
        return f"List<{item_type}>", False
    if kind == "string":
        return "String", False
    if kind == "integer":
        return ("Long" if schema.get("format") == "int64" else "Int"), False
    if kind == "boolean":
        return "Boolean", False
    raise ValueError(f"unsupported OpenAPI type: {schema}")


def render() -> str:
    spec = yaml.safe_load(SPEC_PATH.read_text(encoding="utf-8"))
    schemas = spec["components"]["schemas"]
    lines = [
        "// Generated by openapi/generate.py from docs/api/openapi.yaml. Do not edit manually.",
        "package com.whatto.bcm.admin.client",
        "",
        "import com.fasterxml.jackson.annotation.JsonIgnoreProperties",
        "",
    ]
    for schema_name in SCHEMAS:
        schema = schemas[schema_name]
        required = set(schema.get("required", []))
        lines.append("@JsonIgnoreProperties(ignoreUnknown = true)")
        lines.append(f"data class {CLASS_NAMES[schema_name]}(")
        for property_name, property_schema in schema.get("properties", {}).items():
            property_type, inherently_nullable = kotlin_type(property_schema)
            nullable = inherently_nullable or property_name not in required
            suffix = "?" if nullable else ""
            default = " = null" if nullable else ""
            lines.append(f"    val {property_name}: {property_type}{suffix}{default},")
        lines.append(")")
        lines.append("")
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    expected = render()
    if args.check:
        actual = OUTPUT.read_text(encoding="utf-8") if OUTPUT.exists() else ""
        if actual != expected:
            raise SystemExit("generated BCM Admin OpenAPI types are stale; run openapi/generate.py")
        return
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(expected, encoding="utf-8")


if __name__ == "__main__":
    main()
