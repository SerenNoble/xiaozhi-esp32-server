package xiaozhi.modules.agent.service;

import java.util.List;

import xiaozhi.modules.agent.vo.PersonaCandidateVO;
import xiaozhi.modules.device.entity.DeviceEntity;

public interface PersonaMatcherService {

    /**
     * 为单个用户做一次匹配(保守:仅当另一角色置信度高出当前 Δ 才切换)。
     * 跳过 manual=1(由调用方保证)。
     *
     * @param userId     用户
     * @param days       取最近多少天聊天
     * @param limit      最多取多少条
     * @param minHistory 历史不足此数则不匹配(冷启动)
     */
    void matchForUser(Long userId, int days, int limit, int minHistory);

    /**
     * 列出全部候选角色(system_prompt 非空的全局角色池,与自动匹配同源)。
     */
    List<PersonaCandidateVO> listCandidatePersonas();

    /**
     * 对所有「有设备的非 manual 用户」各做一次匹配(seed 新用户 + 重评估已有)。
     * 遍历用户(来自设备),而非已有 assignment —— 否则空表永远 seed 不了新用户。
     */
    void matchAllNonManualUsers();

    /**
     * 冷启动匹配:按设备扩展字段(孩子信息)从乐宝模板中 LLM 选型,写入 matched_template_id。
     * 由 DeviceExtController.saveExt 异步调用;manual=1 用户跳过。
     */
    void matchColdStart(String deviceId);

    /**
     * 在乐宝模板中按孩子信息 JSON 选型,返回模板 id;无法选出返回 null(供冷启动与画像同步复用)。
     */
    String selectTemplateId(String childInfoJson);

    /**
     * 家长手动切换乐宝角色:将指定模板话术 seed 进用户全部设备的绑定 agent,
     * 并标记 manual=1(自动匹配/画像同步不再覆盖)。
     */
    void switchToTemplate(Long userId, String templateId);

    /**
     * 将指定乐宝模板话术 seed 进某设备的绑定 agent 的 systemPrompt(供冷启动/画像同步/手动切换复用)。
     */
    void seedTemplateToDevice(DeviceEntity device, String templateId);
}
