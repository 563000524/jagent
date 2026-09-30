package com.fastagent.service;

/**
 * 业务校验失败（消息为空、未配置 Key 等）。
 *
 * <p>校验在受理阶段<b>同步</b>完成，界面直接捕获本异常并提示用户，
 * 不会进入流式通道，因此也不会留下半截回答。
 */
public class ApiException extends RuntimeException {

    /** 保留状态码语义，便于将来若重新引入 HTTP 层时直接复用 */
    private final int status;

    public ApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
