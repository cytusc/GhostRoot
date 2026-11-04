package com.hoyo.ghostroot;

import com.hoyo.ghostroot.ILogCallback;

interface IRootRunner {
    boolean isReady();
    /** v33: 会话绑定 —— 服务在每次执行前下发本次会话 ID；usvc 据此清除
     * 上一会话的结果文件，并把该 ID 写进 .gr_verdict。服务端 finish()
     * 只接受 session 精确匹配的结果，彻底杜绝旧结果串用。 */
    void prepareSession(String sessionId);
    void run(String cliPath, String profilePath, int settleSeconds, ILogCallback callback);
    void rebootDevice();
    /** v49: ZN 一键修复 —— 手机端手动触发的 Zygisk Next 自愈链：
     * 清闩锁/自禁用 -> 补单例 daemon -> zygote_states 判定（必要时软重启） ->
     * dex2oat-flags 清除（工行保活）-> 状态回读。rc: 0=完成, 2=忙碌,
     * 3=root 未落地, 其他=失败。 */
    void znRepair(ILogCallback callback);
}
