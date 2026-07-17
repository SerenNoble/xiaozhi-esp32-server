<template>
  <div class="device-item">
    <div style="display: flex;justify-content: space-between;">
    <el-tooltip :content="device.agentName" placement="top" effect="light">
      <div class="device-item-title">
        {{ device.agentName }}
      </div>
    </el-tooltip>
      <div>
        <img src="@/assets/home/delete.png" alt="" style="width: 18px;height: 18px;margin-right: 10px;"
          @click.stop="handleDelete" />
        <el-tooltip class="item" effect="light" :content="device.systemPrompt" placement="top"
          popper-class="device-item-tooltip"> 
          <img src="@/assets/home/info.png" alt="" style="width: 18px;height: 18px;" />
        </el-tooltip>
      </div>
    </div>
    <div v-if="persona.templateName" class="persona-badge">
      <span class="persona-label">乐宝角色</span>
      <span class="persona-name">{{ persona.templateName }}</span>
      <el-tag :type="persona.manual === 1 ? 'warning' : 'success'" size="mini" effect="plain">
        {{ persona.manual === 1 ? '手动' : '自动' }}
      </el-tag>
    </div>
    <div class="device-name">
      {{ $t('home.languageModel') }}：{{ device.llmModelName }}
    </div>
    <div class="device-name">
      {{ $t('home.voiceModel') }}：{{ device.ttsModelName }} ({{ device.ttsVoiceName }})
    </div>
    <div style="display: flex;gap: 10px;align-items: center;">
      <div class="settings-btn" @click="handleConfigure">
        {{ $t('home.configureRole') }}
      </div>
      <div v-if="featureStatus.voiceprintRecognition" class="settings-btn" @click="handleVoicePrint">
        {{ $t('home.voiceprintRecognition') }}
      </div>
      <div class="settings-btn" @click="handleDeviceManage">
        {{ $t('home.deviceManagement') }}({{ device.deviceCount }})
      </div>
      <div :class="['settings-btn', { 'disabled-btn': device.memModelId === 'Memory_nomem' }]"
        @click="handleChatHistory">
        <el-tooltip effect="light" v-if="device.memModelId === 'Memory_nomem'" :content="$t('home.enableMemory')" placement="top">
          <span>{{ $t('home.chatHistory') }}</span>
        </el-tooltip>
        <span v-else>{{ $t('home.chatHistory') }}</span>
      </div>
      <div class="settings-btn" @click="openChildDialog">配置乐宝</div>
    </div>
    <div class="version-info">
      <div>{{ $t('home.lastConversation') }}：{{ formattedLastConnectedTime }}</div>
      <el-tooltip :content="tags.join()" placement="top" effect="light">
        <div class="version-info-scroll">
          {{ tags.join() }}
        </div>
      </el-tooltip>
    </div>
    <el-dialog title="配置乐宝角色" :visible.sync="showChildDialog" width="440px" @open="loadChildExt">
      <el-form :model="childForm" label-width="86px">
        <el-form-item label="孩子年龄段"><el-input v-model="childForm.childAgeRange" placeholder="如 4-7 岁" /></el-form-item>
        <el-form-item label="孩子性格"><el-input v-model="childForm.childPersonality" placeholder="如 活泼好动、喜欢挑战" /></el-form-item>
        <el-form-item label="家长期望"><el-input v-model="childForm.parentGoals" placeholder="如 培养勇气与专注" /></el-form-item>
        <el-form-item label="家长关注"><el-input v-model="childForm.parentConcerns" placeholder="如 情绪管理" /></el-form-item>
        <el-form-item label="内容偏好"><el-input v-model="childForm.contentPreference" placeholder="如 科普、故事" /></el-form-item>
      </el-form>
      <div v-if="matchResult" class="match-result">已为你匹配：<b>{{ matchResult }}</b></div>
      <span slot="footer" class="dialog-footer">
        <el-button @click="showChildDialog = false">取消</el-button>
        <el-button type="primary" :loading="matching" @click="saveChildInfo">保存并匹配</el-button>
      </span>
    </el-dialog>
  </div>
</template>

<script>
import i18n from '@/i18n';
import Api from '@/apis/api';
import Persona from '@/apis/module/persona';
import Device from '@/apis/module/device';

export default {
  name: 'DeviceItem',
  props: {
    device: { type: Object, required: true },
    featureStatus: {
      type: Object,
      default: () => ({
        voiceprintRecognition: false,
        voiceClone: false,
        knowledgeBase: false
      })
    }
  },
  data() {
    return {
      switchValue: false,
      persona: { templateName: '', matchSource: '', manual: 0, fallbackFlag: 0, reason: '' },
      showChildDialog: false,
      matching: false,
      matchResult: '',
      realDeviceId: '',
      childForm: {
        childAgeRange: '',
        childPersonality: '',
        parentGoals: '',
        parentConcerns: '',
        contentPreference: ''
      }
    }
  },
  computed: {
    formattedLastConnectedTime() {
      if (!this.device.lastConnectedAt) return this.$t('home.noConversation');

      const lastTime = new Date(this.device.lastConnectedAt);
      const now = new Date();
      const diffMinutes = Math.floor((now - lastTime) / (1000 * 60));

      if (diffMinutes <= 1) {
        return this.$t('home.justNow');
      } else if (diffMinutes < 60) {
        return this.$t('home.minutesAgo', { minutes: diffMinutes });
      } else if (diffMinutes < 24 * 60) {
        const hours = Math.floor(diffMinutes / 60);
        const minutes = diffMinutes % 60;
        return this.$t('home.hoursAgo', { hours, minutes });
      } else {
        return this.device.lastConnectedAt;
      }
    },
    tags() {
      if (!this.device.tags) return [];
      return this.device.tags.map((tag) => tag.tagName);
    }
  },
  methods: {
    handleDelete() {
      this.$emit('delete', this.device.agentId)
    },
    handleConfigure() {
      this.$router.push({ path: '/role-config', query: { agentId: this.device.agentId } });
    },
    handleVoicePrint() {
      this.$router.push({ path: '/voice-print', query: { agentId: this.device.agentId } });
    },
    handleDeviceManage() {
      this.$router.push({ path: '/device-management', query: { agentId: this.device.agentId } });
    },
    handleChatHistory() {
      if (this.device.memModelId === 'Memory_nomem') {
        return
      }
      this.$emit('chat-history', { agentId: this.device.agentId, agentName: this.device.agentName })
    },
    fetchPersona() {
      Persona.current(({ data }) => {
        if (data && data.code === 0 && data.data) {
          const d = data.data;
          this.persona = {
            templateName: d.templateName || '',
            matchSource: d.matchSource || '',
            manual: d.manual || 0,
            fallbackFlag: d.fallbackFlag || 0,
            reason: d.reason || ''
          };
        }
      });
    },
    resolveDeviceId() {
      // home 列表项 id 实为 agentId,需反查真实设备 id 才能操作 /device/{id}/ext
      Api.device.getAgentBindDevices(this.device.agentId, ({ data }) => {
        if (data && data.code === 0 && data.data && data.data.length) {
          this.realDeviceId = data.data[0].id;
        } else {
          this.realDeviceId = '';
        }
      });
    },
    openChildDialog() {
      if (!this.realDeviceId) {
        this.$message.warning('该智能体未绑定设备，无法配置乐宝角色');
        return;
      }
      this.matchResult = '';
      this.showChildDialog = true;
    },
    loadChildExt() {
      Device.getExt(this.realDeviceId, ({ data }) => {
        const obj = data && data.code === 0 && data.data ? data.data : {};
        this.childForm = {
          childAgeRange: obj.childAgeRange || '',
          childPersonality: obj.childPersonality || '',
          parentGoals: obj.parentGoals || '',
          parentConcerns: obj.parentConcerns || '',
          contentPreference: obj.contentPreference || ''
        };
      });
    },
    saveChildInfo() {
      if (!this.realDeviceId) {
        this.$message.warning('该智能体未绑定设备，无法配置乐宝角色');
        return;
      }
      this.matching = true;
      const extObj = {};
      Object.keys(this.childForm).forEach(k => { if (this.childForm[k]) extObj[k] = this.childForm[k]; });
      Device.saveExt(this.realDeviceId, extObj, () => {
        this.$message.success('已保存，正在为你匹配乐宝角色');
        setTimeout(() => this.refreshPersonaAfterMatch(), 2500);
      });
    },
    refreshPersonaAfterMatch() {
      Persona.current(({ data }) => {
        if (data && data.code === 0 && data.data) {
          const d = data.data;
          this.persona = {
            templateName: d.templateName || '',
            matchSource: d.matchSource || '',
            manual: d.manual || 0,
            fallbackFlag: d.fallbackFlag || 0,
            reason: d.reason || ''
          };
          this.matchResult = d.templateName ? d.templateName : '匹配中，请稍后刷新';
        }
        this.matching = false;
      });
    }
  },
  mounted() {
    this.resolveDeviceId();
    this.fetchPersona();
  },
}
</script>
<style lang="scss" scoped>
.device-item {
  width: 342px;
  border-radius: 20px;
  background: #fafcfe;
  padding: 22px 22px 14px;
  box-sizing: border-box;
  &-title {
    flex: 1;
    font-weight: bold;
    font-size: 18px;
    color: #3d4566;
    text-align: left;
    text-overflow: ellipsis;
    white-space: nowrap;
    overflow: hidden;
  }
}

.device-name {
  margin: 7px 0 10px;
  font-weight: 400;
  font-size: 11px;
  color: #3d4566;
  text-align: left;
}

.settings-btn {
  font-weight: 500;
  font-size: 12px;
  color: #5778ff;
  background: #e6ebff;
  width: auto;
  padding: 0 12px;
  height: 21px;
  line-height: 21px;
  cursor: pointer;
  border-radius: 14px;
}

.version-info {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 15px;
  font-size: 12px;
  color: #979db1;
  font-weight: 400;
  &-scroll {
    margin-left: 20px;
    flex: 1;
    overflow: hidden;
    text-overflow: ellipsis;
    text-wrap: nowrap;
    text-align: right;
  }
}

.persona-badge {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 10px 0 2px;
  &-label {
    font-size: 12px;
    color: #909399;
  }
  &-name {
    font-size: 14px;
    font-weight: 600;
    color: #2F7CF6;
  }
}

.match-result {
  margin-top: 12px;
  padding: 8px 12px;
  background: #e6f0ff;
  border-radius: 8px;
  font-size: 13px;
  color: #303133;
}

.more-tag {
  cursor: pointer;
  flex-shrink: 0;
}

.all-tags-popover {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.disabled-btn {
  background: #e6e6e6;
  color: #999;
  cursor: not-allowed;
}
</style>

<style>
.device-item-tooltip {
  max-height: 60vh !important;
  max-width: 400px !important;
  overflow-y: auto !important;
  scrollbar-width: thin;
  word-break: break-word;
}

.device-item-tooltip .popper__arrow {
  display: none !important;
}

.device-item-tooltip[x-placement^="top"] .popper__arrow {
  border-top-color: transparent !important;
}

.device-item-tooltip[x-placement^="bottom"] .popper__arrow {
  border-bottom-color: transparent !important;
}
</style>