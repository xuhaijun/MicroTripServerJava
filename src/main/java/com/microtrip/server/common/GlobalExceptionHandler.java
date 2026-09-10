package com.microtrip.server.common;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 全局异常处理：所有控制器抛出的异常在此统一收敛为
 * <code>{ "error": { "code": "...", "message": "..." } }</code>。
 *
 * <p>说明：未携带 / 无效令牌这类<b>认证失败</b>由 Security 层
 * （{@code AuthenticationEntryPoint} / JWT 过滤器）直接写出 401，
 * 不经过此处，但包络结构一致。</p>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常：状态码 / 错误码 / 消息全部由 BizException 决定 */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<Map<String, ApiError>> handleBiz(BizException ex) {
        if (ex.getHttpStatus().is5xxServerError()) {
            log.error("[BizException-5xx] {}", ex.getMessage(), ex);
        }
        return ResponseEntity.status(ex.getHttpStatus())
                .body(Map.of("error", new ApiError(ex.getCode(), ex.getMessage())));
    }

    /** 参数校验失败（@Valid / @Validated） */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, ApiError>> handleValid(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElse("参数校验失败");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", new ApiError("ERROR", msg)));
    }

    /** 请求体 JSON 解析失败 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, ApiError>> handleNotReadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", new ApiError("ERROR", "请求体格式错误")));
    }

    /** 路由不存在（需开启 throwExceptionIfNoHandlerFound） */
    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<Map<String, ApiError>> handleNotFound(NoHandlerFoundException ex,
                                                                HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error",
                        new ApiError("NOT_FOUND", "接口不存在: " + req.getMethod() + " " + ex.getRequestURL())));
    }

    /** 已认证但越权：普通用户调用需 ADMIN 角色的管理接口时，由 @PreAuthorize 抛出 AccessDeniedException */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, ApiError>> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", new ApiError("ERROR", "无访问权限")));
    }

    /** HTTP 方法不被允许（如用 POST 调用仅支持 DELETE 的路由）→ 405，而非兜底成 500 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, ApiError>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(Map.of("error", new ApiError("METHOD_NOT_ALLOWED",
                        "请求方法不被允许: " + ex.getMethod())));
    }

    /** 资源不存在（Spring 6 对未匹配路径抛出）→ 404，而非兜底成 500 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, ApiError>> handleNoResource(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", new ApiError("NOT_FOUND", "接口不存在: " + ex.getResourcePath())));
    }

    /** 兜底：服务端内部错误（生产环境不暴露细节） */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, ApiError>> handleOther(Exception ex) {
        log.error("[Unhandled Exception]", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", new ApiError("ERROR", "服务器内部错误")));
    }
}
