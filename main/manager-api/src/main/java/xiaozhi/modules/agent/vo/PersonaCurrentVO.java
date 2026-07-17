package xiaozhi.modules.agent.vo;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 当前匹配乐宝角色(设备级,按登录用户反查)。
 * templates 为 5 个乐宝模板简表,供前端高亮「已选中」效果。
 */
@Data
@Schema(description = "当前匹配乐宝角色")
public class PersonaCurrentVO {

    @Schema(description = "当前匹配模板ID(null=尚未匹配)")
    private String templateId;

    @Schema(description = "当前匹配模板名")
    private String templateName;

    @Schema(description = "0=自动;1=家长手动")
    private Integer manual;

    @Schema(description = "匹配来源 cold_start/cold_start_default/profile_sync/weekly/manual")
    private String matchSource;

    @Schema(description = "匹配置信度")
    private BigDecimal score;

    @Schema(description = "匹配理由")
    private String reason;

    @Schema(description = "1=角色不足已兜底")
    private Integer fallbackFlag;

    @Schema(description = "最近匹配时间")
    private Date matchedAt;

    @Schema(description = "5 个乐宝模板简表(用于高亮)")
    private List<TemplateBrief> templates;

    @Data
    @Schema(description = "乐宝模板简表")
    public static class TemplateBrief {
        @Schema(description = "模板ID")
        private String id;
        @Schema(description = "模板名")
        private String name;
        @Schema(description = "一句话特征(来自 match_meta_json)")
        private String metaSummary;
    }
}
