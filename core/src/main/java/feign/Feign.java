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

import feign.Logger.NoOpLogger;
import feign.ReflectiveFeign.ParseHandlersByName;
import feign.Request.Options;
import feign.Target.HardCodedTarget;
import feign.codec.Decoder;
import feign.codec.Encoder;
import feign.codec.ErrorDecoder;
import feign.querymap.FieldQueryMapEncoder;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static feign.ExceptionPropagationPolicy.NONE;

/**
 * Feign's purpose is to ease development against http apis that feign restfulness. <br>
 * In implementation, Feign is a {@link Feign#newInstance factory} for generating {@link Target
 * targeted} http apis.
 */
public abstract class Feign {

  public static Builder builder() {
    return new Builder();
  }

  /**
   * Configuration keys are formatted as unresolved <a href=
   * "http://docs.oracle.com/javase/6/docs/jdk/api/javadoc/doclet/com/sun/javadoc/SeeTag.html" >see
   * tags</a>. This method exposes that format, in case you need to create the same value as
   * {@link MethodMetadata#configKey()} for correlation purposes.
   *
   * <p>
   * Here are some sample encodings:
   *
   * <pre>
   * <ul>
   *   <li>{@code Route53}: would match a class {@code route53.Route53}</li>
   *   <li>{@code Route53#list()}: would match a method {@code route53.Route53#list()}</li>
   *   <li>{@code Route53#listAt(Marker)}: would match a method {@code
   * route53.Route53#listAt(Marker)}</li>
   *   <li>{@code Route53#listByNameAndType(String, String)}: would match a method {@code
   * route53.Route53#listAt(String, String)}</li>
   * </ul>
   * </pre>
   * <p>
   * Note that there is no whitespace expected in a key!
   *
   * @param targetType {@link feign.Target#type() type} of the Feign interface.
   * @param method     invoked method, present on {@code type} or its super.
   * @see MethodMetadata#configKey()
   */
  public static String configKey(Class targetType, Method method) {
    StringBuilder builder = new StringBuilder();
    builder.append(targetType.getSimpleName());
    builder.append('#').append(method.getName()).append('(');
    for (Type param : method.getGenericParameterTypes()) {
      param = Types.resolve(targetType, targetType, param);
      builder.append(Types.getRawType(param).getSimpleName()).append(',');
    }
    if (method.getParameterTypes().length > 0) {
      builder.deleteCharAt(builder.length() - 1);
    }
    return builder.append(')').toString();
  }

  /**
   * @deprecated use {@link #configKey(Class, Method)} instead.
   */
  @Deprecated
  public static String configKey(Method method) {
    return configKey(method.getDeclaringClass(), method);
  }

  /**
   * Returns a new instance of an HTTP API, defined by annotations in the {@link Feign Contract},
   * for the specified {@code target}. You should cache this result.
   */
  public abstract <T> T newInstance(Target<T> target);

  public static class Builder {

    /**
     * 请求拦截器集合
     */
    private final List<RequestInterceptor> requestInterceptors =
        new ArrayList<RequestInterceptor>();
    /**
     * Capability 接口集合
     */
    private final List<Capability> capabilities = new ArrayList<>();
    /**
     * 日志级别
     */
    private Logger.Level logLevel = Logger.Level.NONE;
    /**
     * Contract接口,用于解析Feign中的注解
     */
    private Contract contract = new Contract.Default();
    /**
     * Client接口, 用于提交HTTP请求
     */
    private Client client = new Client.Default(null, null);
    /**
     * Retryer接口,重试策略
     */
    private Retryer retryer = new Retryer.Default();
    /**
     * 日志
     */
    private Logger logger = new NoOpLogger();
    /**
     *编码接口
     */
    private Encoder encoder = new Encoder.Default();
    /**
     *解码接口
     */
    private Decoder decoder = new Decoder.Default();
    /**
     * 将对象编码到查询MAP中
     */
    private QueryMapEncoder queryMapEncoder = new FieldQueryMapEncoder();
    /**
     *异常解码器
     */
    private ErrorDecoder errorDecoder = new ErrorDecoder.Default();
    /**
     * 客户端选项
     */
    private Options options = new Options();
    /**
     * InvocationHandlerFactory接口，用于生产InvocationHandler接口实现类
     */
    private InvocationHandlerFactory invocationHandlerFactory =
        new InvocationHandlerFactory.Default();
    /**
     * 是否解码404
     */
    private boolean decode404;
    /**
     * 是否在解码后关闭
     */
    private boolean closeAfterDecode = true;
    /**
     * 异常传播策略
     */
    private ExceptionPropagationPolicy propagationPolicy = NONE;
    /**
     * 是否强制解码
     */
    private boolean forceDecoding = false;

    public Builder logLevel(Logger.Level logLevel) {
      this.logLevel = logLevel;
      return this;
    }

    public Builder contract(Contract contract) {
      this.contract = contract;
      return this;
    }

    public Builder client(Client client) {
      this.client = client;
      return this;
    }

    public Builder retryer(Retryer retryer) {
      this.retryer = retryer;
      return this;
    }

    public Builder logger(Logger logger) {
      this.logger = logger;
      return this;
    }

    public Builder encoder(Encoder encoder) {
      this.encoder = encoder;
      return this;
    }

    public Builder decoder(Decoder decoder) {
      this.decoder = decoder;
      return this;
    }

    public Builder queryMapEncoder(QueryMapEncoder queryMapEncoder) {
      this.queryMapEncoder = queryMapEncoder;
      return this;
    }

    /**
     * Allows to map the response before passing it to the decoder.
     */
    public Builder mapAndDecode(ResponseMapper mapper, Decoder decoder) {
      this.decoder = new ResponseMappingDecoder(mapper, decoder);
      return this;
    }

    /**
     * This flag indicates that the {@link #decoder(Decoder) decoder} should process responses with
     * 404 status, specifically returning null or empty instead of throwing {@link FeignException}.
     * <p>
     * <p/>
     * All first-party (ex gson) decoders return well-known empty values defined by
     * {@link Util#emptyValueOf}. To customize further, wrap an existing {@link #decoder(Decoder)
     * decoder} or make your own.
     * <p>
     * <p/>
     * This flag only works with 404, as opposed to all or arbitrary status codes. This was an
     * explicit decision: 404 -> empty is safe, common and doesn't complicate redirection, retry or
     * fallback policy. If your server returns a different status for not-found, correct via a
     * custom {@link #client(Client) client}.
     *
     * @since 8.12
     */
    public Builder decode404() {
      this.decode404 = true;
      return this;
    }

    public Builder errorDecoder(ErrorDecoder errorDecoder) {
      this.errorDecoder = errorDecoder;
      return this;
    }

    public Builder options(Options options) {
      this.options = options;
      return this;
    }

    /**
     * Adds a single request interceptor to the builder.
     */
    public Builder requestInterceptor(RequestInterceptor requestInterceptor) {
      this.requestInterceptors.add(requestInterceptor);
      return this;
    }

    /**
     * Sets the full set of request interceptors for the builder, overwriting any previous
     * interceptors.
     */
    public Builder requestInterceptors(Iterable<RequestInterceptor> requestInterceptors) {
      this.requestInterceptors.clear();
      for (RequestInterceptor requestInterceptor : requestInterceptors) {
        this.requestInterceptors.add(requestInterceptor);
      }
      return this;
    }

    /**
     * Allows you to override how reflective dispatch works inside of Feign.
     */
    public Builder invocationHandlerFactory(InvocationHandlerFactory invocationHandlerFactory) {
      this.invocationHandlerFactory = invocationHandlerFactory;
      return this;
    }

    /**
     * This flag indicates that the response should not be automatically closed upon completion of
     * decoding the message. This should be set if you plan on processing the response into a
     * lazy-evaluated construct, such as a {@link java.util.Iterator}.
     *
     * </p>
     * Feign standard decoders do not have built in support for this flag. If you are using this
     * flag, you MUST also use a custom Decoder, and be sure to close all resources appropriately
     * somewhere in the Decoder (you can use {@link Util#ensureClosed} for convenience).
     *
     * @since 9.6
     */
    public Builder doNotCloseAfterDecode() {
      this.closeAfterDecode = false;
      return this;
    }

    public Builder exceptionPropagationPolicy(ExceptionPropagationPolicy propagationPolicy) {
      this.propagationPolicy = propagationPolicy;
      return this;
    }

    public Builder addCapability(Capability capability) {
      this.capabilities.add(capability);
      return this;
    }

    /**
     * Internal - used to indicate that the decoder should be immediately called
     */
    Builder forceDecoding() {
      this.forceDecoding = true;
      return this;
    }

    public <T> T target(Class<T> apiType, String url) {
      return target(new HardCodedTarget<T>(apiType, url));
    }

    public <T> T target(Target<T> target) {
      return build().newInstance(target);
    }

    public Feign build() {
      // 对Client进行拓展
      Client client = Capability.enrich(this.client, capabilities);
      // 对Retryer进行拓展
      Retryer retryer = Capability.enrich(this.retryer, capabilities);
      // 对RequestInterceptor进行拓展
      List<RequestInterceptor> requestInterceptors = this.requestInterceptors.stream()
          .map(ri -> Capability.enrich(ri, capabilities))
          .collect(Collectors.toList());
      // 对Logger进行拓展
      Logger logger = Capability.enrich(this.logger, capabilities);
      // 对Contract进行拓展
      Contract contract = Capability.enrich(this.contract, capabilities);
      // 对Options进行拓展
      Options options = Capability.enrich(this.options, capabilities);
      // 对Encoder进行拓展
      Encoder encoder = Capability.enrich(this.encoder, capabilities);
      // 对Decoder进行拓展
      Decoder decoder = Capability.enrich(this.decoder, capabilities);
      // 对InvocationHandlerFactory进行拓展
      InvocationHandlerFactory invocationHandlerFactory =
          Capability.enrich(this.invocationHandlerFactory, capabilities);

      // 对QueryMapEncoder进行拓展
      QueryMapEncoder queryMapEncoder = Capability.enrich(this.queryMapEncoder, capabilities);

      // 构建SynchronousMethodHandler.Factory对象
      SynchronousMethodHandler.Factory synchronousMethodHandlerFactory =
          new SynchronousMethodHandler.Factory(client, retryer, requestInterceptors, logger,
              logLevel, decode404, closeAfterDecode, propagationPolicy, forceDecoding);
      // 创建ParseHandlersByName对象
      ParseHandlersByName handlersByName =
          new ParseHandlersByName(contract, options, encoder, decoder, queryMapEncoder,
              errorDecoder, synchronousMethodHandlerFactory);
      // 创建ReflectiveFeign对象
      return new ReflectiveFeign(handlersByName, invocationHandlerFactory, queryMapEncoder);
    }
  }


  public static class ResponseMappingDecoder implements Decoder {

    private final ResponseMapper mapper;
    private final Decoder delegate;

    public ResponseMappingDecoder(ResponseMapper mapper, Decoder decoder) {
      this.mapper = mapper;
      this.delegate = decoder;
    }

    @Override
    public Object decode(Response response, Type type) throws IOException {
      return delegate.decode(mapper.map(response, type), type);
    }
  }
}
