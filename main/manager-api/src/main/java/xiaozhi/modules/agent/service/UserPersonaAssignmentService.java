package xiaozhi.modules.agent.service;

import java.math.BigDecimal;

import com.baomidou.mybatisplus.extension.service.IService;

import xiaozhi.modules.agent.entity.UserPersonaAssignmentEntity;

public interface UserPersonaAssignmentService extends IService<UserPersonaAssignmentEntity> {

    UserPersonaAssignmentEntity getByUserId(Long userId);

    /** 自动匹配写入(manual=0) */
    void upsertAuto(Long userId, String agentId, BigDecimal score, String reason);

    /** 家长手动切换(manual=1,立即生效) */
    void setManual(Long userId, String agentId);

    /** 恢复自动匹配(manual=0) */
    void resetAuto(Long userId);

    /** 冷启动/画像同步写入模板匹配(manual=0,走 matched_template_id 路径) */
    void upsertColdStart(Long userId, String templateId, BigDecimal score, String reason,
                         BigDecimal divergenceScore, Integer fallbackFlag, String matchSource);

    /** 家长手动切换乐宝模板(manual=1,标 matched_template_id + match_source=manual,自动任务不再覆盖) */
    void upsertManual(Long userId, String templateId, String templateName);
}
