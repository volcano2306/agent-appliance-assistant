"""Bounded, resumable model-call meter. Dependencies are explicitly injected."""
from types import SimpleNamespace
import time
from app.memory import TokenCounter
from app.memory_eval_metrics import atomic_json
from app.memory_eval_cost import validate_prices, reservation_cny, usage_cny, liability_cny
from decimal import Decimal


class BudgetStop(BaseException):
    # Production catches Exception to generate safe responses; budgets must escape.
    pass


class MeteredClient:
    def __init__(self, client, *, max_calls, max_tokens, max_duration, min_interval,
                 journal=None, calls=None, prior_elapsed=0, clock=time.monotonic, sleep=time.sleep, counter=None,
                 max_cost_cny=None, prices=None):
        self.client = client
        self.max_calls, self.max_tokens, self.max_duration = max_calls, max_tokens, max_duration
        self.min_interval, self.journal = min_interval, journal
        self.clock, self.sleep = clock, sleep
        self.calls = list(calls or [])
        if len({call["callId"] for call in self.calls}) != len(self.calls):
            raise ValueError("duplicate_call_id")
        self.reserved_tokens = sum(call["reservedEstimate"] for call in self.calls)
        self.started, self.prior_elapsed = self.clock(), prior_elapsed
        self.last_call = self.started if self.calls else float("-inf")
        self.tag = {}
        self.max_cost_cny = None if max_cost_cny is None else Decimal(str(max_cost_cny))
        self.prices = dict(prices or {})
        if self.max_cost_cny is not None:
            if not self.max_cost_cny.is_finite() or self.max_cost_cny <= 0:
                raise ValueError("positive_cny_budget_required")
            validate_prices(self.prices)
            liability_cny(self.calls)  # Resume must preserve every issued reservation.
        self.counter = counter or TokenCounter()
        self.chat = SimpleNamespace(completions=self)

    @property
    def elapsed(self):
        return self.prior_elapsed + self.clock() - self.started

    def _save(self):
        if self.journal is not None:
            atomic_json(self.journal, dict(calls=self.calls, elapsedSeconds=self.elapsed))

    def annotate(self, start_index, usages):
        rows = self.calls[start_index:]
        if len(rows) != len(usages):
            for row in rows: row["modelPhase"] = "unknown_usage_alignment"
        else:
            for index, (row, usage) in enumerate(zip(rows, usages)):
                row["modelPhase"] = usage.phase
                row["operationAttemptIndex"] = index
        self._save()

    def create(self, **kwargs):
        cost_reservation = None
        if self.max_cost_cny is not None:
            cost_reservation = reservation_cny(kwargs, self.prices)
            if liability_cny(self.calls) + cost_reservation > self.max_cost_cny:
                raise BudgetStop("cny_budget_reached_before_dispatch")
        estimate = self.counter.count({"messages": kwargs.get("messages", []), "tools": kwargs.get("tools", [])}) + kwargs.get("max_tokens", 2000)
        if len(self.calls) >= self.max_calls or self.reserved_tokens + estimate > self.max_tokens:
            raise BudgetStop("call_or_token_budget_reached")
        if self.elapsed >= self.max_duration:
            raise BudgetStop("time_budget_reached")
        while True:
            pause = self.min_interval - (self.clock() - self.last_call)
            if pause <= 0:
                break
            if self.elapsed + pause >= self.max_duration:
                raise BudgetStop("time_budget_reached_during_rate_limit")
            self.sleep(min(pause, 1))
        began = self.clock(); self.last_call = began
        row = {**self.tag, "callId": max((call["callId"] for call in self.calls), default=0) + 1,
            "startedAtUnix": time.time(), "finishedAtUnix": None,
            "elapsedMs": None, "inputTokens": None, "outputTokens": None, "cachedInputTokens": None,
            "reservedEstimate": estimate, "status": "issued_outcome_unknown"}
        self.calls.append(row); self.reserved_tokens += estimate
        if cost_reservation is not None:
            row["costReservedCny"] = str(cost_reservation)
            row["costBasis"] = "frozen_peak_rates_with_uncached_utf8_reservation"
        self._save()  # Durable reservation BEFORE dispatch; a crash cannot erase spend.
        remaining = max(.001, self.max_duration - self.elapsed)
        kwargs["timeout"] = min(float(kwargs.get("timeout", 30)), remaining, 30)
        response = None
        try:
            response = self.client.chat.completions.create(**kwargs)
            return response
        except Exception as exc:
            status = getattr(exc, "status_code", None)
            row["httpStatus"] = status if isinstance(status, int) else None
            if status in {400, 401, 403, 404, 422}:
                # Authentication/unsupported parameters will not fix themselves.
                # Do not spend the rest of a matrix on identical fatal errors.
                raise BudgetStop("fatal_model_api_configuration_error") from None
            raise
        finally:
            usage = getattr(response, "usage", None)
            row.update(elapsedMs=(self.clock() - began) * 1000, finishedAtUnix=time.time(),
                inputTokens=getattr(usage, "prompt_tokens", None),
                outputTokens=getattr(usage, "completion_tokens", None),
                cachedInputTokens=getattr(usage, "prompt_cache_hit_tokens", None),
                status="ok" if response is not None else "error_usage_unknown")
            if cost_reservation is not None:
                settlement = usage_cny(row, self.prices)
                row["budgetCostCny"] = str(cost_reservation if settlement is None else settlement)
                row["costUsageUnknown"] = settlement is None
            self._save()
            if cost_reservation is not None and settlement is not None and settlement > cost_reservation:
                raise BudgetStop("cny_reservation_underestimated_stop")
