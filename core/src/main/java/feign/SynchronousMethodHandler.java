/**
 * Copyright 2012-2020 The Feign Authors
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package feign;

import feign.InvocationHandlerFactory.MethodHandler;
import feign.Request.Options;
import feign.codec.Decoder;
import feign.codec.ErrorDecoder;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static feign.ExceptionPropagationPolicy.UNWRAP;
import static feign.FeignException.errorExecuting;
import static feign.Util.checkNotNull;

final class SynchronousMethodHandler implements MethodHandler {

  /**
   * 最大响应缓冲区大小
   */
  private static final long MAX_RESPONSE_BUFFER_SIZE = 8192L;

  /**
   * 方法元数据
   */
  private final MethodMetadata metadata;
  /**
   * 目标对象
   */
  private final Target<?> target;
  /**
   * 客户端
   */
  private final Client client;
  /**
   * 重试接口
   */
  private final Retryer retryer;
  /**
   * 请求拦截器集合
   */
  private final List<RequestInterceptor> requestInterceptors;
  /**
   * 日志对象
   */
  private final Logger logger;
  /**
   * 日志级别
   */
  private final Logger.Level logLevel;
  /**
   * 请求模板工厂
   */
  private final RequestTemplate.Factory buildTemplateFromArgs;
  /**
   * 额外配置选项
   */
  private final Options options;
  /**
   * 异常传播策略
   */
  private final ExceptionPropagationPolicy propagationPolicy;

  // only one of decoder and asyncResponseHandler will be non-null
  /**
   * 解码器
   */
  private final Decoder decoder;
  /**
   * 异步响应处理器
   */
  private final AsyncResponseHandler asyncResponseHandler;


  private SynchronousMethodHandler(Target<?> target,
                                   Client client,
                                   Retryer retryer,
                                   List<RequestInterceptor> requestInterceptors,
                                   Logger logger,
                                   Logger.Level logLevel,
                                   MethodMetadata metadata,
                                   RequestTemplate.Factory buildTemplateFromArgs,
                                   Options options,
                                   Decoder decoder,
                                   ErrorDecoder errorDecoder,
                                   boolean decode404,
                                   boolean closeAfterDecode,
                                   ExceptionPropagationPolicy propagationPolicy,
                                   boolean forceDecoding) {

    this.target = checkNotNull(target, "target");
    this.client = checkNotNull(client, "client for %s", target);
    this.retryer = checkNotNull(retryer, "retryer for %s", target);
    this.requestInterceptors =
        checkNotNull(requestInterceptors, "requestInterceptors for %s", target);
    this.logger = checkNotNull(logger, "logger for %s", target);
    this.logLevel = checkNotNull(logLevel, "logLevel for %s", target);
    this.metadata = checkNotNull(metadata, "metadata for %s", target);
    this.buildTemplateFromArgs = checkNotNull(buildTemplateFromArgs, "metadata for %s", target);
    this.options = checkNotNull(options, "options for %s", target);
    this.propagationPolicy = propagationPolicy;

    if (forceDecoding) {
      // internal only: usual handling will be short-circuited, and all responses will be passed to
      // decoder directly!
      this.decoder = decoder;
      this.asyncResponseHandler = null;
    } else {
      this.decoder = null;
      this.asyncResponseHandler = new AsyncResponseHandler(logLevel, logger, decoder, errorDecoder,
          decode404, closeAfterDecode);
    }
  }

  @Override
  public Object invoke(Object[] argv) throws Throwable {
    // 请求模板
    RequestTemplate template = buildTemplateFromArgs.create(argv);
    // 从参数表中找到选项配置
    Options options = findOptions(argv);
    // 获取重试对象
    Retryer retryer = this.retryer.clone();
    while (true) {
      try {
        // 执行并且解码
        return executeAndDecode(template, options);
      } catch (RetryableException e) {
        try {
          // 重试策略处理
          retryer.continueOrPropagate(e);
        } catch (RetryableException th) {
          Throwable cause = th.getCause();
          if (propagationPolicy == UNWRAP && cause != null) {
            throw cause;
          } else {
            throw th;
          }
        }
        if (logLevel != Logger.Level.NONE) {
          logger.logRetry(metadata.configKey(), logLevel);
        }
        continue;
      }
    }
  }

  Object executeAndDecode(RequestTemplate template, Options options) throws Throwable {
    // 创建请求对象
    Request request = targetRequest(template);

    if (logLevel != Logger.Level.NONE) {
      logger.logRequest(metadata.configKey(), logLevel, request);
    }

    // 创建响应对象
    Response response;
    // 创建开始时间
    long start = System.nanoTime();
    try {
      // 通过客户端发送请求获取响应对象
      response = client.execute(request, options);
      // ensure the request is set. TODO: remove in Feign 12
      response = response.toBuilder()
          .request(request)
          .requestTemplate(template)
          .build();
    } catch (IOException e) {
      if (logLevel != Logger.Level.NONE) {
        logger.logIOException(metadata.configKey(), logLevel, e, elapsedTime(start));
      }
      throw errorExecuting(request, e);
    }
    // 获取请求消费时间
    long elapsedTime = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);


    // 如果解码器存在则解码后返回
    if (decoder != null) {
      return decoder.decode(response, metadata.returnType());
    }

    // 创建CompletableFuture对象
    CompletableFuture<Object> resultFuture = new CompletableFuture<>();
    // 通过异步处理器处理响应
    asyncResponseHandler.handleResponse(resultFuture, metadata.configKey(), response,
        metadata.returnType(),
        elapsedTime);

    try {
      if (!resultFuture.isDone()) {
        throw new IllegalStateException("Response handling not done");
      }

      // 获取响应结果
      return resultFuture.join();
    } catch (CompletionException e) {
      Throwable cause = e.getCause();
      if (cause != null)
        throw cause;
      throw e;
    }
  }

  long elapsedTime(long start) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
  }

  Request targetRequest(RequestTemplate template) {
    // 请求拦截器的处理
    for (RequestInterceptor interceptor : requestInterceptors) {
      // 应用请求拦截器
      interceptor.apply(template);
    }
    // 对target对象进行请求模板设置
    return target.apply(template);
  }

  Options findOptions(Object[] argv) {
    if (argv == null || argv.length == 0) {
      return this.options;
    }
    return Stream.of(argv)
        .filter(Options.class::isInstance)
        .map(Options.class::cast)
        .findFirst()
        .orElse(this.options);
  }

  static class Factory {

    private final Client client;
    private final Retryer retryer;
    private final List<RequestInterceptor> requestInterceptors;
    private final Logger logger;
    private final Logger.Level logLevel;
    private final boolean decode404;
    private final boolean closeAfterDecode;
    private final ExceptionPropagationPolicy propagationPolicy;
    private final boolean forceDecoding;

    Factory(Client client, Retryer retryer, List<RequestInterceptor> requestInterceptors,
            Logger logger, Logger.Level logLevel, boolean decode404, boolean closeAfterDecode,
            ExceptionPropagationPolicy propagationPolicy, boolean forceDecoding) {
      this.client = checkNotNull(client, "client");
      this.retryer = checkNotNull(retryer, "retryer");
      this.requestInterceptors = checkNotNull(requestInterceptors, "requestInterceptors");
      this.logger = checkNotNull(logger, "logger");
      this.logLevel = checkNotNull(logLevel, "logLevel");
      this.decode404 = decode404;
      this.closeAfterDecode = closeAfterDecode;
      this.propagationPolicy = propagationPolicy;
      this.forceDecoding = forceDecoding;
    }

    public MethodHandler create(Target<?> target,
                                MethodMetadata md,
                                RequestTemplate.Factory buildTemplateFromArgs,
                                Options options,
                                Decoder decoder,
                                ErrorDecoder errorDecoder) {
      return new SynchronousMethodHandler(target, client, retryer, requestInterceptors, logger,
          logLevel, md, buildTemplateFromArgs, options, decoder,
          errorDecoder, decode404, closeAfterDecode, propagationPolicy, forceDecoding);
    }
  }
}
