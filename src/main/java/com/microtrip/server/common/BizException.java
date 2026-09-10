package com.microtrip.server.common;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：携带 HTTP 状态码 + 错误码 + 消息。
 *
 * <p>与 Node 版统一错误契约对齐：最终响应体为
 * <code>{ "error": { "code": "...", "message": "..." } }</code>。</p>
 *
 * <p>约定：
 * <ul>
 *   <li>400 参数错误 → code 业务自定义（如 {@code PHONE_INVALID}）或 {@code ERROR}</li>
 *   <li>401 未登录/令牌失效 → {@code ERROR}</li>
 *   <li>404 资源不存在 → {@code NOT_FOUND}</li>
 *   <li>409 唯一约束冲突 → {@code CONFLICT} / {@code ERROR}</li>
 *   <li>501 功能未配置（Vision）→ {@code NOT_CONFIGURED}</li>
 *   <li>502 第三方调用失败（Vision）→ {@code VISION_ERROR}</li>
 * </ul>
 * </p>
 */
public class BizException extends RuntimeException {

    private final HttpStatus httpStatus;
    private final String code;

    public BizException(HttpStatus httpStatus, String code, String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.code = code;
    }

    /** 400 参数错误 */
    public static BizException badRequest(String message) {
        return new BizException(HttpStatus.BAD_REQUEST, "ERROR", message);
    }

    /** 400 参数错误（带自定义 code） */
    public static BizException badRequest(String code, String message) {
        return new BizException(HttpStatus.BAD_REQUEST, code, message);
    }

    /** 401 未认证 */
    public static BizException unauthorized(String message) {
        return new BizException(HttpStatus.UNAUTHORIZED, "ERROR", message);
    }

    /** 404 资源不存在 */
    public static BizException notFound(String message) {
        return new BizException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    /** 409 冲突 */
    public static BizException conflict(String message) {
        return new BizException(HttpStatus.CONFLICT, "CONFLICT", message);
    }

    /** 403 禁止（封禁 / 无权限） */
    public static BizException forbidden(String message) {
        return new BizException(HttpStatus.FORBIDDEN, "FORBIDDEN", message);
    }

    /** 501 未配置 */
    public static BizException notConfigured(String message) {
        return new BizException(HttpStatus.NOT_IMPLEMENTED, "NOT_CONFIGURED", message);
    }

    /** 502 第三方失败 */
    public static BizException upstreamError(String message) {
        return new BizException(HttpStatus.BAD_GATEWAY, "VISION_ERROR", message);
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    public String getCode() {
        return code;
    }
}
