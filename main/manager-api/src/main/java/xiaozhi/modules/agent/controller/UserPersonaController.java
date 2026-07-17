package xiaozhi.modules.agent.controller;

import java.util.List;
import java.util.Map;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.stream.Collectors;

import lombok.AllArgsConstructor;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.agent.entity.AgentTemplateEntity;
import xiaozhi.modules.agent.service.AgentTemplateService;
import xiaozhi.modules.agent.service.PersonaMatcherService;
import xiaozhi.modules.agent.service.UserPersonaAssignmentService;
import xiaozhi.modules.agent.vo.PersonaCandidateVO;
import xiaozhi.modules.agent.vo.PersonaCurrentVO;
import xiaozhi.modules.agent.entity.UserPersonaAssignmentEntity;
import xiaozhi.modules.security.user.SecurityUser;

@RestController
@RequestMapping("/persona")
@AllArgsConstructor
public class UserPersonaController {

    private final UserPersonaAssignmentService userPersonaAssignmentService;
    private final PersonaMatcherService personaMatcherService;
    private final AgentTemplateService agentTemplateService;

    /** 家长手动切换乐宝角色(立即生效,seed 话术 + 标 manual=1,自动任务不再覆盖) */
    @PostMapping("/switch")
    @RequiresPermissions("sys:role:normal")
    public Result<Void> switchPersona(@RequestBody Map<String, String> body) {
        String templateId = body.get("templateId");
        if (templateId == null || templateId.isBlank()) {
            return new Result<Void>().error("templateId 不能为空");
        }
        Long userId = SecurityUser.getUser().getId();
        personaMatcherService.switchToTemplate(userId, templateId);
        return new Result<Void>();
    }

    /** 恢复自动匹配(manual=0) */
    @PostMapping("/auto")
    @RequiresPermissions("sys:role:normal")
    public Result<Void> resetAuto() {
        Long userId = SecurityUser.getUser().getId();
        userPersonaAssignmentService.resetAuto(userId);
        return new Result<Void>();
    }

    /** 候选角色列表(全局角色池,system_prompt 非空,与自动匹配同源) */
    @GetMapping("/candidates")
    @RequiresPermissions("sys:role:normal")
    public Result<List<PersonaCandidateVO>> candidates() {
        return new Result<List<PersonaCandidateVO>>().ok(personaMatcherService.listCandidatePersonas());
    }

    /** 当前匹配乐宝角色(设备级,按登录用户反查) + 5 模板简表(供高亮) */
    @GetMapping("/current")
    @RequiresPermissions("sys:role:normal")
    public Result<PersonaCurrentVO> current() {
        Long userId = SecurityUser.getUser().getId();
        UserPersonaAssignmentEntity a = userPersonaAssignmentService.getByUserId(userId);
        PersonaCurrentVO vo = new PersonaCurrentVO();
        if (a != null && a.getMatchedTemplateId() != null && !a.getMatchedTemplateId().isBlank()) {
            AgentTemplateEntity tpl = agentTemplateService.getById(a.getMatchedTemplateId());
            if (tpl != null) {
                vo.setTemplateId(tpl.getId());
                vo.setTemplateName(tpl.getAgentName());
                vo.setManual(a.getManual());
                vo.setMatchSource(a.getMatchSource());
                vo.setScore(a.getScore());
                vo.setReason(a.getReason());
                vo.setFallbackFlag(a.getFallbackFlag());
                vo.setMatchedAt(a.getMatchedAt());
            }
        }
        vo.setTemplates(agentTemplateService.list().stream()
                .filter(t -> t.getMatchMetaJson() != null && !t.getMatchMetaJson().isBlank())
                .map(t -> {
                    PersonaCurrentVO.TemplateBrief b = new PersonaCurrentVO.TemplateBrief();
                    b.setId(t.getId());
                    b.setName(t.getAgentName());
                    b.setMetaSummary(metaSummary(t.getMatchMetaJson()));
                    return b;
                }).collect(Collectors.toList()));
        return new Result<PersonaCurrentVO>().ok(vo);
    }

    /** 从 match_meta_json 提取一句话特征,用于模板卡片展示 */
    private String metaSummary(String matchMetaJson) {
        if (matchMetaJson == null || matchMetaJson.isBlank()) {
            return "";
        }
        try {
            cn.hutool.json.JSONObject m = cn.hutool.json.JSONUtil.parseObj(matchMetaJson);
            StringBuilder sb = new StringBuilder();
            if (m.getStr("ageRange") != null) sb.append("年龄段").append(m.getStr("ageRange"));
            Object tags = m.get("personalityTags");
            if (tags instanceof Iterable) {
                StringBuilder t = new StringBuilder();
                for (Object o : (Iterable<?>) tags) {
                    if (t.length() > 0) t.append("、");
                    t.append(o);
                }
                if (t.length() > 0) sb.append(" · 性格").append(t);
            }
            Object goals = m.get("guidanceGoals");
            if (goals instanceof Iterable) {
                StringBuilder g = new StringBuilder();
                for (Object o : (Iterable<?>) goals) {
                    if (g.length() > 0) g.append("、");
                    g.append(o);
                }
                if (g.length() > 0) sb.append(" · 引导").append(g);
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
