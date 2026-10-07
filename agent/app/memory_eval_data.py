"""Deterministic synthetic history fixtures; annotations never enter model input."""
from dataclasses import dataclass, replace
from app.schemas import CompleteTurn, TurnMessage


@dataclass(frozen=True)
class Fact:
    id: str
    key: str
    text: str
    source: str
    valid_from: int
    valid_until: int | None
    supersedes: str | None
    priority: str
    acceptable: tuple[str, ...]
    forbidden: tuple[str, ...] = ()
    required_at: tuple[int, ...] = ()


@dataclass(frozen=True)
class Trajectory:
    id: str
    split: str
    density: int
    turns: tuple[CompleteTurn, ...]
    facts: tuple[Fact, ...]
    probes: tuple[int, ...]

    def active(self, boundary):
        return [fact for fact in self.facts if fact.valid_from <= boundary
                and (fact.valid_until is None or boundary < fact.valid_until)]


def build_trajectory(spec: dict) -> Trajectory:
    facts = [
        Fact("model-old", "型号", spec["model"], "u1", 1, 7, None, "critical", (spec["model"],)),
        Fact("model-new", "型号", spec["newModel"], "u7", 7, None, "model-old", "critical", (spec["newModel"],), (spec["model"],)),
        Fact("budget-old", "预算", spec["budget"], "u2", 2, 12, None, "general", (spec["budget"],)),
        Fact("budget-new", "预算", spec["newBudget"], "u12", 12, None, "budget-old", "general", (spec["newBudget"],), (spec["budget"],)),
        Fact("no-write", "操作限制", "不要提交报修", "u3", 3, None, None, "critical", ("不要提交", "未授权", "没有授权", "不提交"), ("已为你提交", "已为您提交")),
        Fact("not-done", "检查状态", "检查仅是建议，我还没有执行", "u4", 4, None, None, "critical", ("没有执行", "尚未执行", "未执行", "还没做"), ("已经检查",)),
    ]
    extras = [
        ("安全限制", "不能拆机"), ("尺寸限制", "宽度不得超过55厘米"),
        ("时间限制", "晚上十点后不用"), ("待确认事项", "售后联系方式尚待核实"),
        ("家庭情况", "家里有儿童"), ("购买限制", "不买二手"),
        ("安装条件", "只接现有插座"), ("噪声偏好", "低噪声优先"),
        ("配送限制", "仅周末收货"), ("颜色偏好", "白色优先"),
        ("保修状态", "保修资格还未确认"), ("使用条件", "潮湿环境先停用"),
    ]
    extra_count = spec["density"] - 4 - (1 if spec["id"] == "dev-unknown-outcome" else 0)
    for i, (key, value) in enumerate(extras[:extra_count]):
        facts.append(Fact(f"extra-{i}", key, value, "u5", 5, None, None,
                          "critical" if key in {"安全限制", "安装条件", "使用条件"} else "general", (value,)))
    content = {
        1: f"我这次咨询的是{spec['theme']}，型号是{spec['model']}。",
        2: f"我的预算是{spec['budget']}元。我在考虑报修，但尚未确认提交。",
        3: "先只咨询，不要提交报修，也不要替我收藏。",
        4: "你刚才提到的检查只是建议，我还没有执行，不能说检查过了。",
        5: "其他条件是：" + "；".join(f.text for f in facts if f.id.startswith("extra-")),
        7: f"更正一下，刚才型号有误，实际是{spec['newModel']}，不是{spec['model']}。",
        8: "另一会话中讨论过型号DISTRACTOR-X，那不是本次设备；请回到当前设备，不要混用型号。",
        12: f"预算也调整为{spec['newBudget']}元，原来{spec['budget']}元的预算作废。",
    }
    if spec["id"] == "dev-unknown-outcome":
        content[6] = "平台上另一次提交超时，结果未知，不能说成功，也不要重试。"
        facts.append(Fact("unknown", "旧提交状态", "结果未知", "u6", 6, None, None,
                          "critical", ("结果未知", "无法确认"), ("提交成功",)))
    if spec["id"] == "dev-advice-not-done":
        facts = [replace(fact, valid_until=9) if fact.id == "not-done" else fact for fact in facts]
        content[9] = "我现在看过外观，没有明显异常，但问题尚未解决；过滤网仍没有检查。"
        facts.append(Fact("observed-not-fixed", "检查状态", "已看外观但未解决，过滤网未检查", "u9", 9,
            None, "not-done", "critical", ("看过外观", "检查过外观", "查看外观"), ("问题已解决", "已经检查过滤网")))
    if spec["id"] == "dev-reference-pending":
        # Replace an extra condition, retaining the declared active density.
        replaced = next(fact for fact in reversed(facts) if fact.id.startswith("extra-"))
        facts.remove(replaced)
        facts.append(Fact("citation", "引用", "第18页，适用性仍待核实", "a5", 5, None, None,
            "general", ("第18页", "18页"), ("适用性已核实",)))
        content[5] = "其他条件是：" + "；".join(f.text for f in facts if f.id.startswith("extra-"))
    probes = (6, 13, spec["turns"] - 7, spec["turns"])
    # Refresh only the check-status point just before probes, so recent-detail
    # accuracy has real samples while model/budget/cancellation remain distant.
    for seq in sorted(probe - 1 for probe in probes):
        previous = next(fact for fact in facts if fact.key == "检查状态" and fact.valid_from <= seq
                        and (fact.valid_until is None or seq < fact.valid_until))
        facts = [replace(fact, valid_until=seq) if fact.id == previous.id else fact for fact in facts]
        text = previous.text + "；第二个方案暂缓"
        content[seq] = content.get(seq, "") + "补充检查状态：" + text + "。"
        facts.append(Fact(f"recent-check-{seq}", "检查状态", text, f"u{seq}", seq,
            previous.valid_until, previous.id, "critical", previous.acceptable, previous.forbidden))
    questions = ["请解释说明书中的注意事项，不做任何提交。", "给我一个普通的清洁建议，不能拆机。",
                 "仅讨论保养流程，不更改前面的设备和预算。", "我们暂时只了解功能，不要买东西或创建工单。"]
    turns = []
    for seq in range(1, spec["turns"] + 1):
        user = content.get(seq, questions[seq % len(questions)])
        answer = "收到，此信息仅用于本次会话咨询，不执行写操作。" if seq in content else "请遵循说明书的安全注意事项，具体步骤需有适用依据。"
        if seq == 3: answer = "方案一是断电后观察外观，方案二是查看说明书中的过滤网检查步骤；这只是建议，不表示你已经执行。"
        if seq == 5 and spec["id"] == "dev-reference-pending":
            answer = "此前引用第18页的说明，但是否适用于当前型号仍待核实。[引用:synthetic-page18]"
        if spec.get("historyProfile") == "long-consultation-v1":
            answer += ("\n以下内容是阅读说明书的方法说明，尚未核实当前设备的具体操作步骤。"
                "阅读时可以先找到目录中的安全注意事项，再逐项区分适用范围、前提条件和停止条件。"
                "遇到图示应同时查看图题与正文，不能仅凭外观相似就认为部件相同。"
                "如果文字提到可选配件或不同配置，应保留差异，不能把另一配置的描述直接用于当前咨询。"
                "将尚缺依据的问题记为待核实，后续查到原文后再讨论适用性。"
                "这段解释只补充阅读方法，不改变此前的设备信息、操作限制或检查状态，也不表示已经执行任何步骤。")
        elif spec.get("historyProfile") not in (None, "short"):
            raise ValueError("unknown_history_profile")
        turns.append(CompleteTurn(turnSeq=seq, requestId=f"{spec['id']}-{seq}",
            user=TurnMessage(messageId=f"u{seq}", role="user", content=user),
            assistant=TurnMessage(messageId=f"a{seq}", role="assistant", content=answer)))
    predecessors = {}; chained = []
    for fact in sorted(facts, key=lambda fact: (fact.key, fact.valid_from)):
        previous = predecessors.get(fact.key)
        chained.append(replace(fact, supersedes=previous.id if previous else None))
        predecessors[fact.key] = fact
    facts = [replace(fact, required_at=tuple(probe for probe in probes if fact.valid_from <= probe
                and (fact.valid_until is None or probe < fact.valid_until))) for fact in chained]
    return Trajectory(spec["id"], spec["split"], spec["density"], tuple(turns), tuple(facts), probes)


def validate_dataset(manifest: dict):
    trajectories = [build_trajectory(spec) for spec in manifest["trajectories"]]
    assert len({t.id for t in trajectories}) == len(trajectories)
    assert sum(t.split == "dev" for t in trajectories) >= 6
    assert sum(t.split == "heldout" for t in trajectories) >= 6
    assert any(len(t.turns) >= 80 for t in trajectories)
    assert len(manifest["arms"]) == 8 and manifest["repeats"] >= 3
    for trajectory in trajectories:
        assert len(trajectory.probes) >= 4 and len(set(trajectory.probes)) == len(trajectory.probes)
        ids = {message.messageId: turn.turnSeq for turn in trajectory.turns for message in (turn.user, turn.assistant)}
        known = {fact.id for fact in trajectory.facts}
        assert len(known) == len(trajectory.facts)
        for fact in trajectory.facts:
            assert ids[fact.source] == fact.valid_from
            assert fact.valid_until is None or fact.valid_until > fact.valid_from
            assert fact.supersedes is None or fact.supersedes in known
            if fact.supersedes:
                previous = next(value for value in trajectory.facts if value.id == fact.supersedes)
                assert previous.key == fact.key and previous.valid_until == fact.valid_from
            assert all(fact.valid_from <= probe and (fact.valid_until is None or probe < fact.valid_until) for probe in fact.required_at)
        assert len(trajectory.active(len(trajectory.turns))) == trajectory.density
    return trajectories
