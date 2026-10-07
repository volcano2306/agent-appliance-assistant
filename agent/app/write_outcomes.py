"""Bind user-visible write claims to outcomes observed in this Agent turn."""

import re

from app.schemas import AgentRequest, AgentResponse, StructuredAnswer, ToolResult


WRITE_TOOLS = {"add_manual_to_account", "create_repair_order"}
_PREFLIGHT_ERRORS = {
    "login_required", "platform_manual_required", "manual_reference_required",
    "manual_not_found", "manual_selection_required", "explicit_add_request_required",
    "manual_target_requires_confirmation", "manual_target_conflict",
    "manual_query_from_user_required", "explicit_confirmation_required",
    "repair_preview_required", "issue_desc_required", "issue_desc_unverified",
    "invalid_tool_argument_schema", "invalid_tool_arguments", "write_already_attempted",
}
_QUESTION_WORDS = ("怎么", "如何", "怎样", "需要什么", "是否", "？", "?")
_NEGATION_WORDS = ("不要", "先别", "先不", "暂不", "取消", "不确认")


def safe_write_error(error: str) -> str:
    """Do not pass exception text, credentials or personal data to the model."""
    if not error:
        return ""
    allowed = _PREFLIGHT_ERRORS | {"write_result_unknown", "invalid_write_request",
                                   "permission_denied", "write_target_not_found", "manual_lookup_failed"}
    return error if error in allowed else "write_result_unknown"


def _requested_write(request: AgentRequest, name: str) -> bool:
    query = request.query.strip()
    if any(word in query for word in _NEGATION_WORDS + _QUESTION_WORDS):
        return False
    if name == "add_manual_to_account":
        return any(word in query for word in ("收藏", "加入我的说明书", "添加到我的说明书", "保存到我的说明书"))
    return bool(re.search(r"确认.*(?:提交|报修|申请)|(?:提交|报修|申请).*确认|无误.*提交|现在提交", query))


def _positive_claim(text: str, name: str) -> bool:
    patterns = (
        (r"(?:已|已经|成功).{0,16}(?:收藏|加入.{0,5}说明书|添加.{0,5}说明书)",
         r"(?:收藏|加入.{0,5}说明书|添加.{0,5}说明书).{0,8}(?:成功|完成)"),
        (r"(?:已|已经|成功).{0,20}(?:提交|创建|生成|发起).{0,15}(?:报修|维修|工单|申请)",
         r"(?:报修|维修|工单|申请).{0,10}(?:已提交|提交成功|创建成功|已生成|已创建)"),
    )[0 if name == "add_manual_to_account" else 1]
    for clause in re.split(r"[。；;！!\n]", text):
        for pattern in patterns:
            for match in re.finditer(pattern, clause):
                prefix = clause[max(0, match.start() - 12):match.start()]
                if not any(word in prefix for word in ("未", "没有", "无法", "不能", "是否", "如果", "之前", "此前", "历史")):
                    return True
    return False


def _negative_claim(text: str, name: str) -> bool:
    action = r"(?:收藏|加入|添加)" if name == "add_manual_to_account" else r"(?:提交|创建|报修)"
    return bool(re.search(rf"(?:未|没有|未能|无法).{{0,12}}{action}", text))


def _current_turn_claim(request: AgentRequest, text: str, name: str) -> bool:
    return _positive_claim(text, name) and (
        _requested_write(request, name) or bool(re.search(r"已为(?:您|你)|本轮|刚刚|我已", text))
    )


def _status(request: AgentRequest, name: str, result: ToolResult) -> str:
    status = result.write_status
    if status in {"succeeded", "already_exists"}:
        if not result.write_target:
            return "unknown"
        if name == "add_manual_to_account" and request.manualId and result.write_target != request.manualId:
            return "unknown"
        if name == "create_repair_order" and (
            not result.write_identifier
            or not result.write_target.startswith(
                f"{request.userId}|{request.manualId}|{request.productId}|"
            )
        ):
            return "unknown"
    if status:
        return status
    if not result.ok and result.error in _PREFLIGHT_ERRORS:
        return "not_dispatched"
    # A mock or legacy tool's ok=True is not proof that Java persisted a write.
    return "unknown"


def observed_write_status(request: AgentRequest, name: str, result: ToolResult) -> str:
    """Internal receipts follow the same verified status as the visible answer."""
    return _status(request, name, result)


def _message(name: str, status: str, result: ToolResult) -> str:
    manual = name == "add_manual_to_account"
    action = "收藏说明书" if manual else "提交维修申请"
    if status == "succeeded":
        if manual:
            return "已将这本说明书加入“我的说明书”。"
        return f"维修申请已提交，工单号：{result.write_identifier}。"
    if status == "already_exists":
        return "这本说明书此前已在“我的说明书”中，本轮没有重复添加。"
    if status == "not_dispatched":
        hint = "请先登录。" if result.error == "login_required" else (
            "请先选择明确的说明书。" if result.error == "manual_selection_required" else
            "请核对目标、所需信息与确认状态。")
        return f"本轮未执行{action}。{hint}"
    if status == "failed":
        reason = {
            "invalid_write_request": "请求信息无效，请核对后再试。",
            "login_required": "请先登录。",
            "permission_denied": "当前用户无权执行该操作。",
            "write_target_not_found": "目标记录不存在，请核对后再试。",
        }.get(result.error, "请核对信息后再试。")
        return f"本轮未完成{action}，业务服务已明确拒绝请求。{reason}"
    return f"本轮{action}的结果暂无法确认。请先通过平台已有渠道核实，避免重复操作。"


def _visible_text(response: AgentResponse) -> str:
    structured = response.structuredAnswer
    return " ".join((response.answerText,
                     structured.summary if structured else "",
                     structured.basisNote if structured else "",
                     " ".join(structured.steps + structured.cautions) if structured else ""))


def protect_write_response(
    request: AgentRequest,
    response: AgentResponse,
    observations: list[tuple[str, ToolResult]],
    *,
    finalization_failed: bool = False,
) -> AgentResponse:
    """Replace contradictory write claims, leaving ordinary read-only answers alone."""
    combined = _visible_text(response)
    if not observations:
        unsupported = [name for name in WRITE_TOOLS
                       if _current_turn_claim(request, combined, name)]
        if not unsupported:
            return response
        messages = [f"本轮没有执行{'收藏说明书' if name == 'add_manual_to_account' else '提交维修申请'}，不能确认该操作已完成。"
                    for name in sorted(unsupported)]
    else:
        relevant = [(name, result, _status(request, name, result)) for name, result in observations]
        active = [(name, result, status) for name, result, status in relevant
                  if _requested_write(request, name) or _positive_claim(combined, name) or finalization_failed]
        observed_names = {name for name, _, _ in relevant}
        missing_claims = [name for name in WRITE_TOOLS - observed_names
                          if _current_turn_claim(request, combined, name)]
        if not active and not missing_claims:
            return response
        messages = [_message(name, status, result) for name, result, status in active] + [
            f"本轮没有执行{'收藏说明书' if name == 'add_manual_to_account' else '提交维修申请'}，不能确认该操作已完成。"
            for name in sorted(missing_claims)
        ]
        contradiction = bool(missing_claims) or any(
            (status != "succeeded" and _positive_claim(combined, name))
            or (status in {"unknown", "succeeded"} and _negative_claim(combined, name))
            or (name == "create_repair_order" and status == "succeeded" and
                "工单号" in combined and result.write_identifier not in combined)
            for name, result, status in active
        )
        if not finalization_failed and not contradiction:
            # Preserve useful explanations; add a verified status only when absent.
            if any(_positive_claim(combined, name) and status == "succeeded"
                   and (name != "create_repair_order" or result.write_identifier in combined)
                   for name, result, status in active) or any(
                       status != "succeeded" and ("未" in combined or "状态未知" in combined or "无法确认" in combined)
                       for _, _, status in active):
                return response
            response.answerText = "\n".join(messages + [response.answerText])
            if response.structuredAnswer:
                response.structuredAnswer.summary = response.answerText
            return response

    safe_text = "\n".join(messages)
    response.answerText = safe_text
    response.structuredAnswer = StructuredAnswer(summary=safe_text, basisNote="依据本轮写工具的可观察结果。")
    response.insufficientContext = any("无法确认" in message for message in messages)
    if finalization_failed:
        # The model failed, but this application response truthfully reports the write.
        response.status = "ok"
        response.error = "model_finalization_failed"
    return response
