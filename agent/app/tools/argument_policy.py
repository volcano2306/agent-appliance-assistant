"""Validate model tool arguments and resolve internal fields from request context.

Database identifiers are deliberately not part of the model-facing contract.
This module is pure so the same policy can be exercised without network or DB.
"""

import re
from typing import Any

from pydantic import BaseModel, ConfigDict, ValidationError

from app.schemas import ToolContext


class _Arguments(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)


class ManualSearchArguments(_Arguments):
    query: str


class DiagnoseFaultArguments(_Arguments):
    brand: str
    model: str
    fault_code: str
    symptoms: str


class AddManualArguments(_Arguments):
    manual_query: str = ""


class CreateRepairArguments(_Arguments):
    brand: str = ""
    model: str = ""
    fault_code: str = ""
    issue_desc: str
    contact_phone: str = ""
    preferred_time: str = ""
    confirmed: bool


class RecommendProductArguments(_Arguments):
    query: str
    budget_max: float | None
    preferences: list[str]


ARGUMENT_MODELS: dict[str, type[_Arguments]] = {
    "manual_search": ManualSearchArguments,
    "diagnose_fault": DiagnoseFaultArguments,
    "add_manual_to_account": AddManualArguments,
    "create_repair_order": CreateRepairArguments,
    "recommend_internet_products": RecommendProductArguments,
}

_INTERNAL_IDS = {"manual_id", "device_id"}
_CURRENT_TARGET_WORDS = ("这本", "当前", "正在看", "眼前", "这个说明书")


def _from_user(value: str, context: ToolContext) -> bool:
    if not value:
        return True
    normalized = re.sub(r"[^0-9a-z\u4e00-\u9fff]", "", value.casefold())
    return bool(normalized) and any(normalized in re.sub(r"[^0-9a-z\u4e00-\u9fff]", "", item.casefold())
                                    for item in [context.user_query, *context.user_history])


def _in_confirmed_preview(value: str, context: ToolContext) -> bool:
    normalized = re.sub(r"[^0-9a-z\u4e00-\u9fff]", "", value.casefold())
    preview = re.sub(r"[^0-9a-z\u4e00-\u9fff]", "", context.last_assistant_message.casefold())
    return bool(normalized) and normalized in preview


def _confirmed_issue_from_preview(context: ToolContext) -> str:
    """Extract only the issue the user saw and previously reported."""
    preview = context.last_assistant_message
    match = re.search(r"(?:故障(?:描述|现象)?|问题(?:描述)?)[：:=是为\s]+([^；;。.!？?\n]+)", preview)
    if not match:
        return ""
    issue = match.group(1).strip(" ，,：: ")
    return issue if _from_user(issue, context) else ""


def _brand_from_user(value: str, context: ToolContext) -> bool:
    if not value:
        return True
    if context.manual_brand and value.casefold() == context.manual_brand.casefold():
        return True
    # A brand-looking prefix of a model number is not evidence of the brand.
    escaped = re.escape(value)
    return any(re.search(rf"(?:品牌|牌子|厂家)[：:\s]*(?:是|为)?[：:\s]*{escaped}(?![\w-])|(?<![\w-]){escaped}(?:牌|品牌)",
                         item, re.IGNORECASE) for item in [context.user_query, *context.user_history])


def _current_target_requested(context: ToolContext) -> bool:
    query = context.user_query.strip()
    if any(word in query for word in ("那本", "另一本", "别的", "其他那本")):
        return False
    if any(word in query for word in _CURRENT_TARGET_WORDS):
        return True
    return any(value and value.casefold() in query.casefold()
               for value in (context.manual_name, context.manual_model))


def prepare_arguments(name: str, raw: dict[str, Any], context: ToolContext) -> tuple[dict[str, Any] | None, str]:
    model = ARGUMENT_MODELS.get(name)
    if model is None:
        return None, "unknown_tool"
    # Ignore model-supplied database IDs. The business layer takes verified IDs
    # from context; accepting an arbitrary model ID can target a different record.
    args = {key: value for key, value in raw.items() if key not in _INTERNAL_IDS}
    try:
        parsed = model.model_validate(args).model_dump()
    except ValidationError:
        return None, "invalid_tool_argument_schema"

    if name in {"manual_search", "recommend_internet_products"} and not parsed["query"].strip():
        return None, "query_required"
    if name == "diagnose_fault" and not (parsed["fault_code"].strip() or parsed["symptoms"].strip()):
        return None, "fault_code_or_symptoms_required"
    if name == "add_manual_to_account":
        query = context.user_query.strip()
        if not any(word in query for word in ("收藏", "加入", "添加", "保存到")) or any(
            word in query for word in ("怎么", "如何", "怎样", "不要", "别", "先不")
        ):
            return None, "explicit_add_request_required"
        if context.manual_id:
            if not _current_target_requested(context):
                return None, "manual_target_requires_confirmation"
            if parsed["manual_query"] and not any(
                value and value.casefold() in parsed["manual_query"].casefold()
                for value in (context.manual_name, context.manual_model)
            ):
                return None, "manual_target_conflict"
            parsed["manual_query"] = ""
        elif context.manual_ref_id:
            return None, "platform_manual_required"
        elif not parsed["manual_query"] or not _from_user(parsed["manual_query"], context):
            return None, "manual_query_from_user_required"
    if name == "create_repair_order":
        query = context.user_query.strip()
        if (not parsed["confirmed"]
                or any(word in query for word in ("不要", "先别", "暂不", "取消", "不确认", "了没", "了吗", "是否"))
                or not re.search(r"确认.*(?:提交|报修|申请)|(?:提交|报修|申请).*确认|无误.*提交|现在提交", query)):
            return None, "explicit_confirmation_required"
        if not re.search(r"待提交|是否确认|确认提交|报修信息", context.last_assistant_message):
            return None, "repair_preview_required"
        if context.unconfirmed_change:
            return None, "repair_preview_required"
        if not parsed["issue_desc"].strip():
            return None, "issue_desc_required"
        issue_desc = parsed["issue_desc"].strip()
        if not (_in_confirmed_preview(issue_desc, context) and _from_user(issue_desc, context)):
            confirmed_issue = _confirmed_issue_from_preview(context)
            normalized_confirmed = re.sub(r"[^0-9a-z\u4e00-\u9fff]", "", confirmed_issue.casefold())
            normalized_requested = re.sub(r"[^0-9a-z\u4e00-\u9fff]", "", issue_desc.casefold())
            if not normalized_confirmed or normalized_confirmed not in normalized_requested:
                return None, "issue_desc_unverified"
            # Submit the user's confirmed wording, not model-added explanation.
            parsed["issue_desc"] = confirmed_issue
        # Optional fields without evidence are omitted, not invented or sent to Java.
        for key in ("brand", "model", "fault_code", "contact_phone", "preferred_time"):
            value = parsed[key].strip()
            if key == "brand":
                known = _brand_from_user(value, context)
            elif key == "model" and context.manual_model:
                known = value.casefold() == context.manual_model.casefold() or _from_user(value, context)
            else:
                known = _from_user(value, context)
            if not known or (value and not _in_confirmed_preview(value, context)):
                parsed[key] = ""
    return parsed, ""
