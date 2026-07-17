"""
OpenAI LLM 客户端
"""
import json
import logging
from typing import List, Dict, Any

try:
    from openai import AsyncOpenAI
    OPENAI_AVAILABLE = True
except ImportError:
    OPENAI_AVAILABLE = False

from .base import LLMClient

logger = logging.getLogger(__name__)


class OpenAIClient(LLMClient):
    """OpenAI API 客户端"""

    def __init__(
        self,
        api_key: str,
        model: str = "gpt-4o-mini",
        base_url: str = None,
        use_json_mode: bool = None
    ):
        if not OPENAI_AVAILABLE:
            raise ImportError("openai package is required. Install with: pip install openai")

        self.api_key = api_key
        self.model = model
        self.client = AsyncOpenAI(api_key=api_key, base_url=base_url)
        # 自动检测是否使用 JSON 模式（豆包等模型不支持）
        self.use_json_mode = use_json_mode if use_json_mode is not None else self._supports_json_mode()

    def _supports_json_mode(self) -> bool:
        """检测模型是否支持 JSON 模式"""
        # 豆包模型不支持 JSON 模式
        if "doubao" in self.model.lower() or self.model.startswith("ep-"):
            return False
        # gpt-4o-mini, gpt-4o, gpt-3.5-turbo 等支持
        return True

    async def extract_facts(
        self,
        conversation: str,
        context: Dict[str, Any],
        return_raw: bool = False
    ) -> List[Dict[str, Any]]:
        """
        从对话中提取事实

        Args:
            conversation: 对话文本
            context: 上下文信息
            return_raw: 如果为 True，返回完整 JSON 对象（含 dangers 字段）

        Returns:
            提取的事实列表，或完整 JSON 对象（return_raw=True 时）
        """
        from prompts.fact_extraction import FACT_EXTRACTION_PROMPT

        prompt = FACT_EXTRACTION_PROMPT.format(**context, conversation=conversation)

        try:
            # 构建请求参数
            request_params = {
                "model": self.model,
                "messages": [
                    {"role": "system", "content": "你是一个专业的个人信息提取助手。"},
                    {"role": "user", "content": prompt}
                ],
                "temperature": 0.3,
            }

            # 只有支持的模型才使用 JSON 模式
            if self.use_json_mode:
                request_params["response_format"] = {"type": "json_object"}

            response = await self.client.chat.completions.create(**request_params)

            result = json.loads(response.choices[0].message.content)

            if return_raw:
                return result

            facts = result.get("facts", [])

            logger.info(f"Extracted {len(facts)} facts from conversation")
            return facts

        except json.JSONDecodeError as e:
            logger.error(f"Failed to parse LLM response as JSON: {e}")
            return [] if not return_raw else {"facts": [], "dangers": []}
        except Exception as e:
            logger.error(f"Failed to extract facts: {e}")
            raise

    async def generate_summary(
        self,
        conversation: str,
        context: Dict[str, Any]
    ) -> str:
        """从对话中生成会话摘要（陪伴卡），为 3-7 岁儿童对话场景设计"""
        prompt = (
            f"当前日期：{context.get('current_date', '未知')}\n"
            "你是一个儿童语音伴侣的会话摘要员。请根据以下对话，生成一段简短摘要（不超过120字，一段话），"
            "用儿童的视角和语气，必须包含：\n"
            "1. 孩子今天聊了哪些话题（1-2句话）\n"
            "2. 有什么未完成的事（如：讲到一半的故事、约定的游戏、计划要做的事，如果有的话）\n"
            "3. 孩子的情绪状态（开心/兴奋/委屈/难过/平静）\n"
            "行为指引：若孩子仅简单打招呼（如只说\"你好\"），不要写开场白式内容，直接注明\"无实质内容\"；"
            "若之前有未完成的约定/游戏/故事，要在摘要里点明，方便下次主动提起、自然延续话题。\n"
            "请直接返回摘要文本，不要加任何前缀或解释。语气温暖亲切，像朋友的口吻。\n\n"
            f"对话内容：\n{conversation}"
        )

        try:
            response = await self.client.chat.completions.create(
                model=self.model,
                messages=[
                    {"role": "system", "content": "你是一个温暖细心的儿童记录员，善于捕捉孩子对话中的关键信息。"},
                    {"role": "user", "content": prompt}
                ],
                temperature=0.5,
                max_tokens=200,
            )
            summary = response.choices[0].message.content.strip()
            logger.info(f"Generated session summary: {summary[:80]}...")
            return summary
        except Exception as e:
            logger.error(f"Failed to generate session summary: {e}")
            raise
