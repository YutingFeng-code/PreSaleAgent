import asyncio

from agents.agent_orchestrator import AgentOrchestrator, AgentType, ProductAdvisorAgent, Request
from agents.tools import product_catalog_tools
from core.intent_recognizer import IntentCategory


class FakeClient:
    class Messages:
        async def create(self, **kwargs):
            raise AssertionError("catalog tool test should not call the LLM")

    messages = Messages()


def test_presales_intents_route_to_product_advisor():
    assert AgentType.PRODUCT_ADVISOR.value == "product_advisor"
    for intent in (
        IntentCategory.PRODUCT_COMPARE,
        IntentCategory.PRODUCT_RECOMMEND,
        IntentCategory.SPEC_INQUIRY,
        IntentCategory.AVAILABILITY,
        IntentCategory.PRICE_PROMOTION,
    ):
        assert AgentOrchestrator._INTENT_ROUTING[intent] is AgentType.PRODUCT_ADVISOR


def test_product_advisor_profile_and_catalog_fallback(monkeypatch):
    agent = ProductAdvisorAgent(FakeClient(), "test-model")
    assert agent.profile.temperature == 0.6
    assert "search_product_catalog" in agent.get_tools()

    monkeypatch.delenv("ECHOMIND_CATALOG_API_URL", raising=False)
    req = Request(message="推荐一款适合通勤的产品", user_id="u", conv_id="c")
    result = asyncio.run(agent.get_tools()["search_product_catalog"].handler(req, {"query": "通勤"}))
    assert result["success"] is False
    assert result["available"] is False
    assert result["results"] == []
