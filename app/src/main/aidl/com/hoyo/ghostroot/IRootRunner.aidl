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
}
