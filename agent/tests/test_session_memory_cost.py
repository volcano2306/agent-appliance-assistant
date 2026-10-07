from types import SimpleNamespace
import json
import pytest
from app.memory_eval_runtime import MeteredClient, BudgetStop
from app.memory_eval_cost import liability_cny, reservation_cny

PRICES = dict(currency="CNY", inputPerMillion=2, cachedInputPerMillion=.04, outputPerMillion=8)

class Client:
    def __init__(self): self.chat = SimpleNamespace(completions=self); self.count = 0
    def create(self, **kwargs):
        self.count += 1
        return SimpleNamespace(usage=SimpleNamespace(prompt_tokens=100, completion_tokens=10, prompt_cache_hit_tokens=80))

def meter(client, **kwargs):
    return MeteredClient(client, max_calls=1000, max_tokens=10000000, max_duration=100,
                         min_interval=0, prices=PRICES, **kwargs)

def test_money_cap_stops_before_dispatch():
    client = Client(); tested = meter(client, max_cost_cny=.001)
    with pytest.raises(BudgetStop, match="cny_budget"):
        tested.create(messages=[], max_tokens=2000)
    assert client.count == 0 and not tested.calls

def test_reservation_persisted_before_dispatch_and_usage_refunds(tmp_path):
    client = Client(); path = tmp_path / "calls.json"
    original = client.create
    def inspect(**kwargs):
        call = json.loads(path.read_text())["calls"][0]
        assert float(call["costReservedCny"]) > .001
        assert call["status"] == "issued_outcome_unknown"
        return original(**kwargs)
    client.chat.completions = SimpleNamespace(create=inspect)
    tested = meter(client, max_cost_cny=2, journal=path)
    tested.create(messages=[], max_tokens=10)
    assert float(liability_cny(tested.calls)) == pytest.approx(.0001232)
    assert not tested.calls[0]["costUsageUnknown"]

def test_unknown_usage_stays_reserved_after_resume(tmp_path):
    client = Client()
    def fail(**kwargs): raise RuntimeError("synthetic failure")
    client.chat.completions = SimpleNamespace(create=fail)
    limit = float(reservation_cny(dict(messages=[], max_tokens=10), PRICES))
    tested = meter(client, max_cost_cny=limit)
    with pytest.raises(RuntimeError): tested.create(messages=[], max_tokens=10)
    assert tested.calls[0]["costUsageUnknown"]
    resumed = meter(Client(), max_cost_cny=limit, calls=tested.calls)
    with pytest.raises(BudgetStop): resumed.create(messages=[], max_tokens=10)
    assert resumed.client.count == 0

def test_missing_cache_uses_uncached_rate():
    client = Client()
    client.chat.completions = SimpleNamespace(create=lambda **kwargs: SimpleNamespace(
        usage=SimpleNamespace(prompt_tokens=100, completion_tokens=10)))
    tested = meter(client, max_cost_cny=2); tested.create(messages=[], max_tokens=10)
    assert float(liability_cny(tested.calls)) == pytest.approx(.00028)

@pytest.mark.parametrize("limit", [0, -1, float("nan"), float("inf")])
def test_invalid_limit_rejected(limit):
    with pytest.raises(ValueError): meter(Client(), max_cost_cny=limit)

def test_old_journal_without_money_reservation_rejected():
    with pytest.raises(ValueError, match="reservation_missing"):
        meter(Client(), max_cost_cny=2, calls=[dict(callId=1, reservedEstimate=10)])

def test_unexpected_usage_over_reservation_halts_run():
    client = Client()
    client.chat.completions = SimpleNamespace(create=lambda **kwargs: SimpleNamespace(
        usage=SimpleNamespace(prompt_tokens=10000, completion_tokens=10, prompt_cache_hit_tokens=0)))
    tested = meter(client, max_cost_cny=2)
    with pytest.raises(BudgetStop, match="underestimated"):
        tested.create(messages=[], max_tokens=10)
    assert float(liability_cny(tested.calls)) == pytest.approx(.02008)
