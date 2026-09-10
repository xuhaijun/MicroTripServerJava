package com.microtrip.server.common;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 统一错误体的内部错误对象：<code>{ "code": "...", "message": "..." }</code>。
 * 外层由 {@link GlobalExceptionHandler} 包裹为 <code>{ "error": {...} }</code>。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(String code, String message) {
}
