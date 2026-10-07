"""Conservative CNY budget accounting, independent of credentials and providers."""
from decimal import Decimal
import json


def validate_prices(prices):
    if prices.get("currency") != "CNY":
        raise ValueError("cny_prices_required")
    for key in ("inputPerMillion", "cachedInputPerMillion", "outputPerMillion"):
        value = Decimal(str(prices.get(key)))
        if not value.is_finite() or value < 0:
            raise ValueError("valid_prices_required")
    if prices["cachedInputPerMillion"] > prices["inputPerMillion"]:
        raise ValueError("cached_price_exceeds_input")


def reservation_cny(kwargs, prices):
    # One UTF-8 byte per text token plus 4096 tokens of framing allowance.
    # Unlike the experiment tokenizer, this reservation ignores cache savings.
    content = json.dumps({"messages": kwargs.get("messages", []), "tools": kwargs.get("tools", [])},
                         ensure_ascii=False, separators=(",", ":"))
    input_upper = len(content.encode("utf-8")) + 4096
    output_upper = kwargs.get("max_tokens", 2000)
    if type(output_upper) is not int or output_upper < 1:
        raise ValueError("output_limit_required")
    return (Decimal(input_upper) * Decimal(str(prices["inputPerMillion"]))
            + Decimal(output_upper) * Decimal(str(prices["outputPerMillion"]))) / 1_000_000


def usage_cny(call, prices):
    input_tokens, output_tokens = call.get("inputTokens"), call.get("outputTokens")
    if input_tokens is None or output_tokens is None:
        return None
    cached = call.get("cachedInputTokens")
    cached = 0 if cached is None else cached  # Unknown cache: charge all input as uncached.
    if any(type(value) is not int or value < 0 for value in (input_tokens, output_tokens, cached)) or cached > input_tokens:
        raise ValueError("invalid_provider_usage")
    return (Decimal(input_tokens - cached) * Decimal(str(prices["inputPerMillion"]))
            + Decimal(cached) * Decimal(str(prices["cachedInputPerMillion"]))
            + Decimal(output_tokens) * Decimal(str(prices["outputPerMillion"]))) / 1_000_000


def liability_cny(calls):
    total = Decimal(0)
    for call in calls:
        value = call.get("budgetCostCny", call.get("costReservedCny"))
        if value is None:
            raise ValueError("resume_cost_reservation_missing")
        amount = Decimal(str(value))
        if not amount.is_finite() or amount < 0:
            raise ValueError("invalid_cost_reservation")
        total += amount
    return total
