package xiaozhi.modules.agent.task;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import cn.hutool.core.date.DateUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import xiaozhi.modules.agent.entity.AgentTemplateEntity;
import xiaozhi.modules.agent.entity.UserPersonaAssignmentEntity;
import xiaozhi.modules.device.entity.DeviceExtEntity;
import xiaozhi.modules.agent.service.AgentTemplateService;
import xiaozhi.modules.agent.service.DingTalkNotifier;
import xiaozhi.modules.agent.service.GrowthPersonaClient;
import xiaozhi.modules.agent.service.PersonaMatcherService;
import xiaozhi.modules.agent.service.UserPersonaAssignmentService;
import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.device.service.DeviceExtService;

/**
 * 外部画像同步任务:读取成长服务 growth_service.db 的 13 维儿童画像(extension_fields),
 * 1) 合并进 ai_device_ext(使 prompt 的 {{ext.*}} 占位符在键名重合时自动注入最新画像);
 * 2) 以最新画像重跑乐宝模板匹配,更新 matched_template_id(match_source=profile_sync);
 * 3) 画像与当前模板偏差过大或无法匹配时,兜底默认模板并钉钉通知管理员。
 *
 * 每日 04:00 跑一次;外部库不可用/设备无画像则安全跳过,不阻断。
 */
@Component
@AllArgsConstructor
@Slf4j
public class PersonaProfileSyncTask {

    private final PersonaMatcherService personaMatcherService;
    private final GrowthPersonaClient growthPersonaClient;
    private final DeviceExtService deviceExtService;
    private final UserPersonaAssignmentService userPersonaAssignmentService;
    private final AgentTemplateService agentTemplateService;
    private final DeviceDao deviceDao;
    private final DingTalkNotifier dingTalkNotifier;

    /** 每日 04:00 */
    @Scheduled(cron = "0 0 4 * * ?")
    public void syncAll() {
        List<DeviceEntity> devices = deviceDao.selectList(null).stream()
                .filter(d -> d.getUserId() != null)
                .collect(Collectors.toList());
        log.info("[persona-profile-sync] 开始同步,设备 {} 台", devices.size());
        int updated = 0;
        for (DeviceEntity d : devices) {
            try {
                if (syncOne(d)) {
                    updated++;
                }
            } catch (Exception e) {
                log.warn("[persona-profile-sync] device={} 异常:{}", d.getId(), e.getMessage());
            }
        }
        log.info("[persona-profile-sync] 同步完成,更新 {} 台", updated);
    }

    /** @return 是否发生匹配写入 */
    private boolean syncOne(DeviceEntity device) {
        Long userId = device.getUserId();
        String deviceId = device.getId();

        UserPersonaAssignmentEntity cur = userPersonaAssignmentService.getByUserId(userId);
        if (cur != null && cur.getManual() != null && cur.getManual() == 1) {
            return false; // 家长手动锁定,跳过
        }

        String profileJson = growthPersonaClient.getProfileJson(deviceId);
        if (profileJson == null || profileJson.isBlank()) {
            return false; // 外部库不可用 / 无画像
        }

        // 1) 合并画像到 device_ext(保留已有键,外部字段覆盖同名键)
        mergeProfileToDeviceExt(deviceId, profileJson);

        // 2) 以最新画像重跑模板匹配
        String chosenId = personaMatcherService.selectTemplateId(profileJson);
        if (chosenId == null) {
            AgentTemplateEntity def = agentTemplateService.getDefaultTemplate();
            String defId = (def != null && def.getId() != null)
                    ? def.getId() : firstTemplateId();
            personaMatcherService.seedTemplateToDevice(device, defId);
            userPersonaAssignmentService.upsertColdStart(userId, defId, BigDecimal.ZERO,
                    "外部画像未匹配到合适乐宝,已兜底默认", null, 1, "profile_sync");
            dingTalkNotifier.notify("【乐宝角色匹配兜底】用户 " + userId + " 设备 " + deviceId
                    + " 外部画像未匹配到合适乐宝模板,已兜底默认角色,请关注是否需要扩充角色库。");
            log.warn("[persona-profile-sync] device={} 未匹配,兜底 template={}", deviceId, defId);
            return true;
        }

        // 3) 计算偏差分(画像与当前模板元数据差异)
        BigDecimal divergence = computeDivergence(profileJson, cur);
        BigDecimal score = (divergence == null) ? BigDecimal.ZERO : BigDecimal.ONE.subtract(divergence);
        boolean changed = cur == null || !chosenId.equals(cur.getMatchedTemplateId());
        if (changed) {
            // 仅当角色真正切换时才 seed,避免覆盖家长在角色配置里的手动编辑
            personaMatcherService.seedTemplateToDevice(device, chosenId);
        }
        userPersonaAssignmentService.upsertColdStart(userId, chosenId, score,
                "外部画像同步匹配", divergence, 0, "profile_sync");
        if (changed) {
            log.info("[persona-profile-sync] device={} 切换到 template={} divergence={}",
                    deviceId, chosenId, divergence);
        }
        return changed;
    }

    private void mergeProfileToDeviceExt(String deviceId, String profileJson) {
        DeviceExtEntity ext = deviceExtService.getByDeviceId(deviceId);
        JSONObject merged;
        if (ext != null && ext.getExtJson() != null && !ext.getExtJson().isBlank()) {
            merged = JSONUtil.parseObj(ext.getExtJson());
        } else {
            merged = new JSONObject();
        }
        JSONObject profile = JSONUtil.parseObj(profileJson);
        profile.forEach((k, v) -> merged.put(k, v));
        merged.put("_externalSyncedAt", DateUtil.now());
        deviceExtService.saveOrUpdate(deviceId, merged.toString());
    }

    /** 画像与「当前匹配模板元数据」的偏差分(0~1,越大越不匹配);无当前模板或解析失败返回 null。 */
    private BigDecimal computeDivergence(String profileJson, UserPersonaAssignmentEntity cur) {
        if (cur == null || cur.getMatchedTemplateId() == null) {
            return null;
        }
        AgentTemplateEntity tpl = agentTemplateService.getById(cur.getMatchedTemplateId());
        if (tpl == null || tpl.getMatchMetaJson() == null || tpl.getMatchMetaJson().isBlank()) {
            return null;
        }
        try {
            JSONObject tMeta = JSONUtil.parseObj(tpl.getMatchMetaJson());
            JSONObject p = JSONUtil.parseObj(profileJson);
            java.util.Set<String> tTokens = tokenSet(tMeta);
            if (tTokens.isEmpty()) {
                return null;
            }
            java.util.Set<String> pTokens = tokenSet(p);
            long hit = tTokens.stream().filter(pTokens::contains).count();
            double overlap = (double) hit / tTokens.size();
            double div = 1.0 - overlap;
            return BigDecimal.valueOf(div).setScale(2, BigDecimal.ROUND_HALF_UP);
        } catch (Exception e) {
            return null;
        }
    }

    private java.util.Set<String> tokenSet(JSONObject obj) {
        java.util.Set<String> set = new java.util.HashSet<>();
        obj.forEach((k, v) -> {
            if (v instanceof Iterable) {
                for (Object o : (Iterable<?>) v) {
                    if (o != null) addTokens(set, o.toString());
                }
            } else if (v != null) {
                addTokens(set, v.toString());
            }
        });
        return set;
    }

    private void addTokens(java.util.Set<String> set, String s) {
        for (String w : s.toLowerCase().split("[\\s,，、/]+")) {
            if (!w.isBlank()) {
                set.add(w);
            }
        }
    }

    private String firstTemplateId() {
        return agentTemplateService.list().stream()
                .filter(t -> t.getMatchMetaJson() != null && !t.getMatchMetaJson().isBlank())
                .map(AgentTemplateEntity::getId)
                .findFirst().orElse(null);
    }
}
