package xiaozhi.modules.agent.service.impl;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import xiaozhi.modules.agent.dao.AgentDao;
import xiaozhi.modules.agent.entity.AgentChatHistoryEntity;
import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.agent.entity.UserPersonaAssignmentEntity;
import xiaozhi.modules.agent.entity.AgentTemplateEntity;
import xiaozhi.modules.agent.service.AgentTemplateService;
import xiaozhi.modules.agent.service.AgentChatHistoryService;
import xiaozhi.modules.agent.service.DingTalkNotifier;
import xiaozhi.modules.agent.service.PersonaMatcherService;
import xiaozhi.modules.agent.service.UserPersonaAssignmentService;
import xiaozhi.modules.agent.vo.PersonaCandidateVO;
import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.device.entity.DeviceExtEntity;
import xiaozhi.modules.device.service.DeviceExtService;
import xiaozhi.modules.llm.service.LLMService;

@Slf4j
@Service
public class PersonaMatcherServiceImpl implements PersonaMatcherService {

    /** 仅当新角色置信度高出当前 ≥ 此值才切换 */
    private static final BigDecimal SWITCH_SCORE_DELTA = new BigDecimal("0.20");

    @Autowired private AgentDao agentDao;
    @Autowired private AgentChatHistoryService agentChatHistoryService;
    @Autowired private DeviceDao deviceDao;
    @Autowired private LLMService llmService;
    @Autowired private UserPersonaAssignmentService userPersonaAssignmentService;
    @Autowired private AgentTemplateService agentTemplateService;
    @Autowired private DeviceExtService deviceExtService;
    @Autowired private DingTalkNotifier dingTalkNotifier;

    @Override
    public List<PersonaCandidateVO> listCandidatePersonas() {
        return agentDao.selectList(null).stream()
                .filter(a -> a.getSystemPrompt() != null && !a.getSystemPrompt().isBlank())
                .map(a -> {
                    PersonaCandidateVO vo = new PersonaCandidateVO();
                    vo.setId(a.getId());
                    vo.setAgentName(a.getAgentName());
                    return vo;
                })
                .collect(Collectors.toList());
    }

    @Override
    public void matchAllNonManualUsers() {
        // 遍历「有设备的用户」(而非已有 assignment —— 否则空表永远 seed 不了新用户)
        java.util.Set<Long> userIds = deviceDao.selectList(null).stream()
                .map(DeviceEntity::getUserId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        log.info("[persona-match] 开始匹配,候选用户 {} 人", userIds.size());
        int matched = 0;
        for (Long userId : userIds) {
            UserPersonaAssignmentEntity a = userPersonaAssignmentService.getByUserId(userId);
            if (a != null && a.getManual() != null && a.getManual() == 1) {
                continue; // 家长手动设定,跳过
            }
            try {
                matchForUser(userId, 14, 50, 5);
                matched++;
            } catch (Exception ex) {
                log.warn("[persona-match] user={} 失败:{}", userId, ex.getMessage());
            }
        }
        log.info("[persona-match] 匹配完成,处理 {} 人", matched);
    }

    @Override
    public void matchForUser(Long userId, int days, int limit, int minHistory) {
        // 0. 取当前角色,manual=1 的用户跳过(保护手动设定,不覆盖)
        UserPersonaAssignmentEntity cur = userPersonaAssignmentService.getByUserId(userId);
        if (cur != null && cur.getManual() != null && cur.getManual() == 1) {
            log.info("[persona-match] user={} manual=1, skip", userId);
            return;
        }

        // 1. 取该用户所有设备的 mac
        List<DeviceEntity> devices = deviceDao.selectList(
                new LambdaQueryWrapper<DeviceEntity>().eq(DeviceEntity::getUserId, userId));
        if (devices.isEmpty()) {
            return;
        }
        List<String> macs = devices.stream().map(DeviceEntity::getMacAddress).collect(Collectors.toList());

        // 2. 取近期孩子消息(chat_type=1)
        Date cutoff = Date.from(LocalDateTime.now().minusDays(days)
                .atZone(ZoneId.systemDefault()).toInstant());
        List<AgentChatHistoryEntity> msgs = agentChatHistoryService.list(
                new LambdaQueryWrapper<AgentChatHistoryEntity>()
                        .in(AgentChatHistoryEntity::getMacAddress, macs)
                        .eq(AgentChatHistoryEntity::getChatType, 1)
                        .gt(AgentChatHistoryEntity::getCreatedAt, cutoff)
                        .orderByDesc(AgentChatHistoryEntity::getCreatedAt)
                        .last("LIMIT " + limit));
        if (msgs.size() < minHistory) {
            log.info("[persona-match] user={} 历史不足({}<{})跳过", userId, msgs.size(), minHistory);
            return;
        }

        // 3. 候选角色(system_prompt 非空)
        List<AgentEntity> candidates = agentDao.selectList(null).stream()
                .filter(a -> a.getSystemPrompt() != null && !a.getSystemPrompt().isBlank())
                .collect(Collectors.toList());
        if (candidates.isEmpty()) {
            return;
        }

        // 4. 拼主题 + 候选,调 LLM
        String topics = msgs.stream().map(AgentChatHistoryEntity::getContent)
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.joining("\n"));
        String conversation = buildConversation(topics, candidates, cur);
        log.info("[persona-match] user={} 候选{}个 主题{}字符", userId, candidates.size(), topics.length());
        String promptTemplate = buildInstruction();
        String resp;
        try {
            resp = llmService.generateSummary(conversation, promptTemplate, null);
        } catch (Exception e) {
            log.warn("[persona-match] user={} LLM 调用失败:{}", userId, e.getMessage());
            return;
        }

        // 5. 解析 + 校验编号 + 高阈值才写
        MatchResult mr = parse(resp);
        if (mr == null) {
            log.warn("[persona-match] user={} LLM 返回无法解析:{}", userId, resp);
            return;
        }
        // 校验编号在候选范围内(防 LLM 编造;不让 LLM 抄 id,只返回编号)
        if (mr.choice < 1 || mr.choice > candidates.size()) {
            log.warn("[persona-match] user={} LLM 返回 choice={} 超出范围(1~{}),跳过", userId, mr.choice, candidates.size());
            return;
        }
        String chosenAgentId = candidates.get(mr.choice - 1).getId();
        boolean shouldSwitch = cur == null
                || !chosenAgentId.equals(cur.getAgentId())
                && (cur.getScore() == null || mr.score.subtract(cur.getScore()).compareTo(SWITCH_SCORE_DELTA) >= 0);
        if (!shouldSwitch) {
            log.info("[persona-match] user={} 保留当前 agent={}(new={},score={})", userId,
                    cur == null ? null : cur.getAgentId(), chosenAgentId, mr.score);
            return;
        }
        userPersonaAssignmentService.upsertAuto(userId, chosenAgentId, mr.score, mr.reason);
        log.info("[persona-match] user={} 切换到 agent={} score={} reason={}", userId, chosenAgentId, mr.score, mr.reason);
    }

    @Async
    @Override
    public void matchColdStart(String deviceId) {
        DeviceEntity device = deviceDao.selectById(deviceId);
        if (device == null || device.getUserId() == null) {
            return;
        }
        Long userId = device.getUserId();
        UserPersonaAssignmentEntity cur = userPersonaAssignmentService.getByUserId(userId);
        if (cur != null && cur.getManual() != null && cur.getManual() == 1) {
            log.info("[persona-coldstart] user={} manual=1, skip", userId);
            return;
        }
        DeviceExtEntity ext = deviceExtService.getByDeviceId(deviceId);
        String childInfo = (ext != null && ext.getExtJson() != null) ? ext.getExtJson() : "{}";

        List<AgentTemplateEntity> templates = agentTemplateService.list().stream()
                .filter(t -> t.getMatchMetaJson() != null && !t.getMatchMetaJson().isBlank())
                .collect(Collectors.toList());
        if (templates.isEmpty()) {
            log.warn("[persona-coldstart] 无可用乐宝模板 user={}", userId);
            return;
        }

        MatchResult mr = matchTemplates(childInfo, templates);
        if (mr == null || mr.choice < 1 || mr.choice > templates.size()) {
            AgentTemplateEntity def = agentTemplateService.getDefaultTemplate();
            String defId = (def != null && def.getId() != null) ? def.getId() : templates.get(0).getId();
            seedTemplateToDevice(device, defId);
            userPersonaAssignmentService.upsertColdStart(userId, defId, BigDecimal.ZERO,
                    "未匹配到合适乐宝,已兜底默认", null, 1, "cold_start_default");
            dingTalkNotifier.notify("【乐宝角色匹配兜底】用户 " + userId + " 设备 " + deviceId
                    + " 未匹配到合适乐宝模板,已兜底默认角色,请关注是否需要扩充角色库。");
            log.warn("[persona-coldstart] user={} 未匹配,兜底 template={}", userId, defId);
            return;
        }
        AgentTemplateEntity chosen = templates.get(mr.choice - 1);
        seedTemplateToDevice(device, chosen.getId());
        userPersonaAssignmentService.upsertColdStart(userId, chosen.getId(), mr.score, mr.reason, null, 0, "cold_start");
        log.info("[persona-coldstart] user={} 匹配 template={} score={} reason={}", userId, chosen.getId(), mr.score, mr.reason);
    }

    @Override
    public String selectTemplateId(String childInfoJson) {
        List<AgentTemplateEntity> templates = agentTemplateService.list().stream()
                .filter(t -> t.getMatchMetaJson() != null && !t.getMatchMetaJson().isBlank())
                .collect(Collectors.toList());
        if (templates.isEmpty()) {
            return null;
        }
        MatchResult mr = matchTemplates(childInfoJson, templates);
        if (mr == null || mr.choice < 1 || mr.choice > templates.size()) {
            return null;
        }
        return templates.get(mr.choice - 1).getId();
    }

    private MatchResult matchTemplates(String childInfoJson, List<AgentTemplateEntity> templates) {
        StringBuilder sb = new StringBuilder();
        sb.append("【孩子信息(扩展字段)】\n").append(childInfoJson).append("\n\n【候选乐宝角色】\n");
        int idx = 1;
        for (AgentTemplateEntity t : templates) {
            sb.append(idx++).append(". ").append(t.getAgentName()).append(": ").append(t.getMatchMetaJson()).append("\n");
        }
        String promptTemplate = buildTemplateInstruction();
        String resp;
        try {
            resp = llmService.generateSummary(sb.toString(), promptTemplate, null);
        } catch (Exception e) {
            log.warn("[persona-coldstart] LLM 调用失败:{}", e.getMessage());
            return null;
        }
        return parse(resp);
    }

    private String buildTemplateInstruction() {
        return "你是儿童陪伴乐宝角色匹配器。根据【孩子信息(扩展字段)】从【候选乐宝角色】的编号列表里选最贴合的一个,"
                + "每个候选给出结构化元数据(年龄段/性格标签/引导目标/情感支持强度/语言复杂度)。\n"
                + "{conversation}\n\n"
                + "只返回JSON,不要多余文字:{\"choice\":选中角色的编号(整数),\"score\":0.0到1.0,\"reason\":\"一句话\"}";
    }

    private String buildConversation(String topics, List<AgentEntity> candidates, UserPersonaAssignmentEntity cur) {
        StringBuilder sb = new StringBuilder();
        sb.append("【孩子近期聊天】\n").append(topics).append("\n\n【候选陪伴角色】\n");
        int idx = 1;
        for (AgentEntity a : candidates) {
            String desc = a.getSystemPrompt();
            if (desc.length() > 120) desc = desc.substring(0, 120);
            sb.append(idx++).append(". ").append(a.getAgentName()).append(": ").append(desc).append("\n");
        }
        if (cur != null) {
            sb.append("\n【当前角色id】").append(cur.getAgentId());
        }
        return sb.toString();
    }

    private String buildInstruction() {
        return "你是儿童陪伴角色匹配器。根据【孩子近期聊天】从【候选陪伴角色】的编号列表里选最贴合的一个。"
                + "若【当前角色】已足够合适,务必保留当前(孩子需要稳定陪伴)。仅当另一角色明显更贴合时才换。\n\n"
                + "{conversation}\n\n"
                + "只返回JSON,不要多余文字:{\"choice\":选中角色的编号(整数),\"score\":0.0到1.0,\"reason\":\"一句话\"}";
    }

    @Override
    public void seedTemplateToDevice(DeviceEntity device, String templateId) {
        if (device == null || templateId == null) {
            return;
        }
        AgentTemplateEntity tpl = agentTemplateService.getById(templateId);
        seedTemplateToAgent(device, tpl);
    }

    /** 将模板话术 seed 进设备绑定 agent 的 systemPrompt(供家长在角色配置里查看/编辑覆盖) */
    private void seedTemplateToAgent(DeviceEntity device, AgentTemplateEntity template) {
        if (device == null || template == null || device.getAgentId() == null) {
            return;
        }
        AgentEntity agent = agentDao.selectById(device.getAgentId());
        if (agent == null) {
            return;
        }
        String base = template.getSystemPrompt();
        agent.setSystemPrompt(base == null ? "" : base);
        agentDao.updateById(agent);
    }

    /** 家长手动切换乐宝角色:seed 话术 + 标记 manual=1(自动任务不再覆盖) */
    @Override
    public void switchToTemplate(Long userId, String templateId) {
        AgentTemplateEntity tpl = agentTemplateService.getById(templateId);
        if (tpl == null) {
            return;
        }
        List<DeviceEntity> devices = deviceDao.selectList(
                new LambdaQueryWrapper<DeviceEntity>().eq(DeviceEntity::getUserId, userId));
        for (DeviceEntity d : devices) {
            seedTemplateToAgent(d, tpl);
        }
        userPersonaAssignmentService.upsertManual(userId, templateId, tpl.getAgentName());
    }

    private MatchResult parse(String resp) {
        if (resp == null) return null;
        int s = resp.indexOf('{'), e = resp.lastIndexOf('}');
        if (s < 0 || e <= s) return null;
        try {
            JSONObject j = JSONUtil.parseObj(resp.substring(s, e + 1));
            MatchResult mr = new MatchResult();
            mr.choice = j.getInt("choice", 0);
            mr.score = new BigDecimal(j.getStr("score", "0"));
            // score 越界(DB 列 DECIMAL(4,2) 上限 9.99,且语义上仅 [0,1] 有意义)视为不可解析,跳过写入
            if (mr.score.signum() < 0 || mr.score.compareTo(BigDecimal.ONE) > 0) {
                return null;
            }
            mr.reason = j.getStr("reason");
            return mr;
        } catch (Exception ex) {
            return null;
        }
    }

    private static class MatchResult {
        int choice;
        BigDecimal score;
        String reason;
    }
}
