import { getServiceUrl } from '../api';
import RequestService from '../httpRequest';

export default {
    // 候选角色列表(全局角色池,与自动匹配同源)
    candidates(callback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/persona/candidates`)
            .method('GET')
            .success((res) => {
                RequestService.clearRequestTime();
                callback(res);
            })
            .networkFail(() => {
                RequestService.reAjaxFun(() => {
                    this.candidates(callback);
                });
            }).send();
    },
    // 手动切换乐宝角色(templateId,标 manual=1,自动任务不再覆盖)
    switchPersona(templateId, callback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/persona/switch`)
            .method('POST')
            .data({ templateId })
            .success((res) => {
                RequestService.clearRequestTime();
                callback(res);
            })
            .networkFail(() => {
                RequestService.reAjaxFun(() => {
                    this.switchPersona(templateId, callback);
                });
            }).send();
    },
    // 恢复自动匹配(manual=0)
    resetAuto(callback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/persona/auto`)
            .method('POST')
            .success((res) => {
                RequestService.clearRequestTime();
                callback(res);
            })
            .networkFail(() => {
                RequestService.reAjaxFun(() => {
                    this.resetAuto(callback);
                });
            }).send();
    },
    // 当前匹配乐宝角色(设备级,按登录用户反查) + 5 模板简表
    current(callback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/persona/current`)
            .method('GET')
            .success((res) => {
                RequestService.clearRequestTime();
                callback(res);
            })
            .networkFail(() => {
                RequestService.reAjaxFun(() => {
                    this.current(callback);
                });
            }).send();
    },
}
