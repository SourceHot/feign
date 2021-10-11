/**
 * Copyright 2012-2020 The Feign Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package feign;

import static feign.FeignException.errorReading;
import static feign.Util.ensureClosed;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;
import feign.Logger.Level;
import feign.codec.DecodeException;
import feign.codec.Decoder;
import feign.codec.ErrorDecoder;

/**
 * The response handler that is used to provide asynchronous support on top of standard response
 * handling
 *
 * 异步响应处理器
 */
@Experimental
class AsyncResponseHandler {

  private static final long MAX_RESPONSE_BUFFER_SIZE = 8192L;

  private final Level logLevel;
  private final Logger logger;

  private final Decoder decoder;
  private final ErrorDecoder errorDecoder;
  private final boolean decode404;
  private final boolean closeAfterDecode;

  AsyncResponseHandler(Level logLevel, Logger logger, Decoder decoder, ErrorDecoder errorDecoder,
      boolean decode404, boolean closeAfterDecode) {
    super();
    this.logLevel = logLevel;
    this.logger = logger;
    this.decoder = decoder;
    this.errorDecoder = errorDecoder;
    this.decode404 = decode404;
    this.closeAfterDecode = closeAfterDecode;
  }

  boolean isVoidType(Type returnType) {
    return Void.class == returnType || void.class == returnType;
  }

  void handleResponse(CompletableFuture<Object> resultFuture,
                      String configKey,
                      Response response,
                      Type returnType,
                      long elapsedTime) {
    // copied fairly liberally from SynchronousMethodHandler
    // 是否需要关闭
    boolean shouldClose = true;

    try {
      if (logLevel != Level.NONE) {
        response = logger.logAndRebufferResponse(configKey, logLevel, response,
            elapsedTime);
      }
      // 返回值类型如果是Response
      if (Response.class == returnType) {
        if (response.body() == null) {
          // 处理响应
          resultFuture.complete(response);
        }
        // 响应体为空或者响应体大小超过最大缓冲值
        else if (response.body().length() == null
            || response.body().length() > MAX_RESPONSE_BUFFER_SIZE) {
          // 标记不需要关闭
          shouldClose = false;
          // 处理响应
          resultFuture.complete(response);
        }
        // 其他情况
        else {
          // Ensure the response body is disconnected
          // 获取响应对象转换成byte数组
          final byte[] bodyData = Util.toByteArray(response.body().asInputStream());
          // 处理响应
          resultFuture.complete(response.toBuilder().body(bodyData).build());
        }
      }
      // 如果响应码大于等于200并且小于等于300
      else if (response.status() >= 200 && response.status() < 300) {
        // 确认返回值是否是void
        if (isVoidType(returnType)) {
          // 处理响应
          resultFuture.complete(null);
        }
        // 其他情况
        else {
          // 解码获取响应对象
          final Object result = decode(response, returnType);
          shouldClose = closeAfterDecode;
          // 处理响应
          resultFuture.complete(result);
        }
      }
      // 满足下面三个条件则作处理
      // 1. 不需要解码404
      // 2. 响应码为404
      // 3. 响应对象不是void
      else if (decode404 && response.status() == 404 && !isVoidType(returnType)) {
        // 解码获取响应对象
        final Object result = decode(response, returnType);
        // 设置是否需要关闭
        shouldClose = closeAfterDecode;
        // 处理响应
        resultFuture.complete(result);
      }
      // 其他情况
      else {
        // 处理响应
        resultFuture.completeExceptionally(errorDecoder.decode(configKey, response));
      }
    } catch (final IOException e) {
      if (logLevel != Level.NONE) {
        logger.logIOException(configKey, logLevel, e, elapsedTime);
      }
      // 处理响应，设置异常
      resultFuture.completeExceptionally(errorReading(response.request(), response, e));
    } catch (final Exception e) {
      // 处理响应，设置异常
      resultFuture.completeExceptionally(e);
    } finally {
      // 如果需要关闭则进行关闭操作
      if (shouldClose) {
        // 关闭
        ensureClosed(response.body());
      }
    }

  }

  Object decode(Response response, Type type) throws IOException {
    try {
      return decoder.decode(response, type);
    } catch (final FeignException e) {
      throw e;
    } catch (final RuntimeException e) {
      throw new DecodeException(response.status(), e.getMessage(), response.request(), e);
    }
  }
}
