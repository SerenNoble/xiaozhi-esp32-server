from abc import ABC, abstractmethod
from typing import Optional
from config.logger import setup_logging

TAG = __name__
logger = setup_logging()


class MemoryProviderBase(ABC):
    def __init__(self, config):
        self.config = config
        self.role_id = None

    def set_llm(self, llm):
        self.llm = llm

    @abstractmethod
    async def save_memory(self, msgs, session_id=None):
        """Save a new memory for specific role and return memory ID"""
        print("this is base func", msgs)

    @abstractmethod
    async def query_memory(self, query: str) -> str:
        """Query memories for specific role based on similarity"""
        return "please implement query method"

    def init_memory(self, role_id, llm, **kwargs):
        self.role_id = role_id
        self.llm = llm

    async def get_today_schedule(self) -> str:
        """获取当天日程，注入半稳定系统提示词（默认空，子类按需实现）"""
        return ""

    async def record_first_meeting(self) -> int:
        """记录首次见面（默认无操作，子类按需实现）"""
        return 0

    async def get_relationship_milestone(self) -> Optional[str]:
        """关系里程碑文案（默认无，子类按需实现）"""
        return None
