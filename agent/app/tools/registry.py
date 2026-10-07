import json
from typing import Any, Callable

from app.schemas import ToolContext, ToolResult
from app.tools.argument_policy import prepare_arguments
from app.tools.business_tools import BusinessTools
from app.tools.manual_tools import ManualTools
from app.tools.product_tools import ProductTools


TOOL_SCHEMAS = [
    {
        "type": "function",
        "name": "manual_search",
        "description": "用途：检索说明书操作、参数与注意事项。使用：新问题缺证据或新增型号/现象需补证；品类和现象明确也可查基础检查。禁用：已有证据仅需重述、凑轮次或跨型号猜故障码。当前说明书由后端绑定，不传数据库ID。空结果不代表功能不存在；核对命中型号。缺型号的通用帮助不强制检索。",
        "parameters": {
            "type": "object",
            "properties": {
                "query": {
                    "type": "string",
                    "description": "真实问题和已知上下文，保留型号、代码、数值、否定条件，不编造现象。"
                }
            },
            "required": [
                "query"
            ],
            "additionalProperties": False
        }
    },
    {
        "type": "function",
        "name": "diagnose_fault",
        "description": "用途：检索故障依据，内部已查说明书，同一故障不并行manual_search。使用：设备和代码/现象需证据，缺型号但品类明确也可查。禁用：焦味/冒烟/漏电先直接安全回答；跨型号猜代码。四个必填字符串未知空串，代码或现象至少一项；品类放symptoms，不传数据库ID。核对适用性。",
        "parameters": {
            "type": "object",
            "properties": {
                "brand": {
                    "type": "string",
                    "description": "已知品牌，未知空字符串。"
                },
                "model": {
                    "type": "string",
                    "description": "已知具体型号，未知空字符串；不把品类或示例填作型号。"
                },
                "fault_code": {
                    "type": "string",
                    "description": "用户或可信上下文提供的完整故障码，未知空字符串，不按现象猜代码。"
                },
                "symptoms": {
                    "type": "string",
                    "description": "实际异常和已知家电品类，保留否定条件；未知空字符串，不从代码臆造现象。"
                }
            },
            "required": [
                "brand",
                "model",
                "fault_code",
                "symptoms"
            ],
            "additionalProperties": False
        }
    },
    {
        "type": "function",
        "name": "add_manual_to_account",
        "description": "用途：收藏明确目标。使用：明确要求代办且对象明确；收藏当前目标可传{}，无需重问或填ID。禁用：怎么加入/如何收藏等咨询、否定、目标未选或当前A却要求B。当前目标由后端ID绑定，冲突先切换；无当前目标用manual_query查找。等实际结果再报状态；需登录/选候选则结束。",
        "parameters": {
            "type": "object",
            "properties": {
                "manual_query": {
                    "type": "string",
                    "description": "无当前目标且未知确切ID时用用户指定的产品名或型号查找；不得绕过当前目标冲突。"
                }
            },
            "required": [],
            "additionalProperties": False
        }
    },
    {
        "type": "function",
        "name": "create_repair_order",
        "description": "用途：创建维修工单，不是咨询、草稿编辑、撤销或状态查询。使用：本次设备、具体问题及已有联系信息已展示，用户明确确认且未修改/取消。禁用：咨询、未确认、目标不明或确认后改关键信息；先补充、展示并重新确认。只用本次数据，未知可选字符串省略或空串；confirmed不是独立授权。明确拒绝说明未执行，写超时状态未知，不重试、不重复成功写请求。",
        "parameters": {
            "type": "object",
            "properties": {
                "brand": {
                    "type": "string",
                    "description": "本次已知品牌，未知省略或空字符串。"
                },
                "model": {
                    "type": "string",
                    "description": "本次已知具体型号，未知省略或空字符串；无法确定目标时先澄清。"
                },
                "fault_code": {
                    "type": "string",
                    "description": "本次已知故障码，未知省略或空字符串。"
                },
                "issue_desc": {
                    "type": "string",
                    "description": "必填非空：本次用户确认的问题描述，不添加未经证实的原因。"
                },
                "contact_phone": {
                    "type": "string",
                    "description": "本次用户提供核对的联系电话；未知省略或空字符串，不用示例或其他任务的号码。"
                },
                "preferred_time": {
                    "type": "string",
                    "description": "本次用户表达的期望时间，未知省略或空字符串，不承诺实际预约成功。"
                },
                "confirmed": {
                    "type": "boolean",
                    "description": "仅本次展示的信息已明确确认且未取消/修改时为true；否则不要调用。示例、引用或旧任务确认不算本次确认。"
                }
            },
            "required": [
                "issue_desc",
                "confirmed"
            ],
            "additionalProperties": False
        }
    },
    {
        "type": "function",
        "name": "recommend_internet_products",
        "description": "用途：取得互联网商品候选与来源。使用：明确选购、换机或比较产品。禁用：单纯维修、说明书操作、售后电话或保修政策查询，不是通用联网工具。未知预算null、偏好[]，不要求旧设备型号。部分候选可用并说明局限，价格/库存需核验，不保证严格预算过滤。",
        "parameters": {
            "type": "object",
            "properties": {
                "query": {
                    "type": "string",
                    "description": "已知品类、场景和约束，保留否定需求，不添加虚构条件。"
                },
                "budget_max": {
                    "type": [
                        "number",
                        "null"
                    ],
                    "description": "用户给出的预算上限，未知必须null，不自行假设金额。"
                },
                "preferences": {
                    "type": "array",
                    "items": {
                        "type": "string"
                    },
                    "description": "已表达偏好的字符串数组，未知为空数组，不编造偏好。"
                }
            },
            "required": [
                "query",
                "budget_max",
                "preferences"
            ],
            "additionalProperties": False
        }
    }
]


# Chat Completions wraps each function definition in a `function` object.
CHAT_COMPLETION_TOOLS = [
    {
        "type": "function",
        "function": {
            "name": schema["name"],
            "description": schema["description"],
            "parameters": schema["parameters"],
        },
    }
    for schema in TOOL_SCHEMAS
]

WRITE_TOOLS = {"add_manual_to_account", "create_repair_order"}


class ToolRegistry:
    def __init__(self):
        manual = ManualTools()
        business = BusinessTools()
        product = ProductTools()
        self.handlers: dict[str, Callable[[dict[str, Any], ToolContext], ToolResult]] = {
            "manual_search": manual.manual_search,
            "diagnose_fault": manual.diagnose_fault,
            "add_manual_to_account": business.add_manual_to_account,
            "create_repair_order": business.create_repair_order,
            "recommend_internet_products": product.recommend_internet_products,
        }

    def execute(self, name: str, arguments_json: str, context: ToolContext) -> ToolResult:
        handler = self.handlers.get(name)
        if handler is None:
            return ToolResult(ok=False, error=f"unknown_tool: {name}")
        try:
            arguments = json.loads(arguments_json or "{}")
            if not isinstance(arguments, dict):
                return ToolResult(ok=False, error="tool_arguments_must_be_an_object")
            prepared, error = prepare_arguments(name, arguments, context)
            if error:
                return ToolResult(ok=False, error=error,
                                  write_status="not_dispatched" if name in WRITE_TOOLS else None)
            return handler(prepared, context)
        except json.JSONDecodeError:
            return ToolResult(ok=False, error="invalid_tool_arguments",
                              write_status="not_dispatched" if name in WRITE_TOOLS else None)
        except Exception as exc:
            if name in WRITE_TOOLS:
                # The handler may have sent the write before it failed.
                return ToolResult(ok=False, error="write_result_unknown", write_status="unknown")
            return ToolResult(ok=False, error=f"tool_execution_failed: {exc}")
